package com.abhiraj.archyra.ai.chat;

import static com.abhiraj.archyra.ai.FakeProviders.GEMINI;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.ai.ChatIntegrationSupport;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/**
 * The {@code AI_QUERY} quota with the real, small limit of 3: a unit is reserved before the model is called, returned once
 * for a question that fails, never for one that was answered, and a cache hit is free.
 */
@TestPropertySource(properties = "archyra.usage.free-tier.ai-query-limit=3")
class ChatQuotaIntegrationTest extends ChatIntegrationSupport {

	@Autowired
	ChatTransactions transactions;
	@Autowired
	ChatRecovery recovery;

	private static final int LIMIT = 3;

	/** A field of the AI_QUERY entry of a usage response. */
	@SuppressWarnings("unchecked")
	private static <T> T usageField(String usage, String field) {
		return (T) ((List<Object>) JsonPath.read(usage, "$.usage[?(@.usageType=='AI_QUERY')]." + field)).getFirst();
	}

	private int pendingMessages(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM chat_messages m JOIN conversations c ON c.id = m.conversation_id WHERE c.analysis_id = ? AND m.status = 'PENDING'",
				Integer.class, analysisId);
	}

	@Test
	void aFreeUserGetsTheirLifetimeQuestionsAndIsThenRefusedWithNothingStored() throws Exception {
		Ready shop = readyShop();
		for (int i = 1; i <= LIMIT; i++) {
			ask(shop.user(), shop.analysisId(), "Question number " + i, null);
		}
		int messages = storedMessages(shop.analysisId());
		int calls = GEMINI.requestCount();

		MvcResult refused = chat(shop.user(), shop.analysisId(), "One too many", null);

		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(refused)).isEqualTo("USAGE_LIMIT_EXCEEDED");
		assertThat(refused.getResponse().getContentAsString()).contains("3 free AI queries").doesNotContain("One too many");
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(LIMIT);
		assertThat(storedMessages(shop.analysisId())).isEqualTo(messages);
		assertThat(GEMINI.requestCount()).isEqualTo(calls); // no model call was made for it
		String usage = getAs(shop.user(), "/api/v1/usage").getResponse().getContentAsString();
		assertThat((Integer) usageField(usage, "limit")).isEqualTo(LIMIT);
		assertThat((Integer) usageField(usage, "remaining")).isZero();
		assertThat((Boolean) usageField(usage, "limitReached")).isTrue();
	}

	@Test
	void aPremiumUserIsCountedButNeverBlocked() throws Exception {
		Ready shop = readyShop(newUser(Plan.PREMIUM));
		for (int i = 1; i <= LIMIT + 2; i++) {
			ask(shop.user(), shop.analysisId(), "Premium question " + i, null);
		}

		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(LIMIT + 2);
		String usage = getAs(shop.user(), "/api/v1/usage").getResponse().getContentAsString();
		assertThat((Object) usageField(usage, "limit")).isNull();
		assertThat((Boolean) usageField(usage, "limitReached")).isFalse();
	}

	@Test
	void aCacheHitCostsNothingEvenAtTheLimit() throws Exception {
		Ready shop = readyShop();
		ask(shop.user(), shop.analysisId(), "The repeated question", null);
		ask(shop.user(), shop.analysisId(), "Second question", null);
		ask(shop.user(), shop.analysisId(), "Third question", null);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(LIMIT);

		String repeated = ask(shop.user(), shop.analysisId(), "The repeated question", null);

		assertThat((boolean) read(repeated, "$.cached")).isTrue();
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(LIMIT);
		assertThat(chat(shop.user(), shop.analysisId(), "A new one", null).getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void twoParallelQuestionsOneUnitFromTheLimitGiveExactlyOneAnswer() throws Exception {
		Ready shop = readyShop();
		ask(shop.user(), shop.analysisId(), "First of the allowance", null);
		ask(shop.user(), shop.analysisId(), "Second of the allowance", null);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(LIMIT - 1);
		CyclicBarrier together = new CyclicBarrier(2);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String question = "Racing question " + i;
				results.add(threads.submit(() -> {
					together.await(10, TimeUnit.SECONDS);
					return chat(shop.user(), shop.analysisId(), question, null).getResponse().getStatus();
				}));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(60, TimeUnit.SECONDS));
			}

			assertThat(statuses).containsExactlyInAnyOrder(200, 403);
		}
		finally {
			threads.shutdownNow();
		}
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(LIMIT);
		assertThat(pendingMessages(shop.analysisId())).isZero();
	}

	@Test
	void theRefundHappensExactlyOnceHoweverOftenTheFailurePathRuns() throws Exception {
		Ready shop = readyShop();
		ask(shop.user(), shop.analysisId(), "An answered question", null);
		int before = used(shop.user(), "AI_QUERY");
		ChatTransactions.Reservation reservation = transactions.reserve(shop.user().getId(), shop.analysisId(), null, "A question that will fail");
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before + 1);
		assertThat(pendingMessages(shop.analysisId())).isEqualTo(1);

		transactions.refund(shop.user().getId(), reservation.conversationId(), reservation.userMessageId(), reservation.createdConversation());
		transactions.refund(shop.user().getId(), reservation.conversationId(), reservation.userMessageId(), reservation.createdConversation());
		transactions.refund(shop.user().getId(), reservation.conversationId(), reservation.userMessageId(), reservation.createdConversation());

		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before); // not before - 1, not before - 2
		assertThat(pendingMessages(shop.analysisId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE id = ?", Integer.class, reservation.conversationId())).isZero();
	}

	@Test
	void anAnsweredQuestionIsNeverRefundedByALateFailure() throws Exception {
		Ready shop = readyShop();
		String body = ask(shop.user(), shop.analysisId(), "An answered question", null);
		UUID conversationId = UUID.fromString(read(body, "$.conversationId"));
		UUID questionId = jdbc.queryForObject("SELECT id FROM chat_messages WHERE conversation_id = ? AND role = 'USER'", UUID.class, conversationId);
		int before = used(shop.user(), "AI_QUERY");

		transactions.refund(shop.user().getId(), conversationId, questionId, true);

		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before);
		assertThat(storedMessages(shop.analysisId())).isEqualTo(2); // the answered pair is untouched
	}

	@Test
	void startupReturnsTheUnitOfAQuestionThatWasChargedButNeverAnswered() throws Exception {
		Ready shop = readyShop();
		int before = used(shop.user(), "AI_QUERY");
		// the server stopped between the reservation and the answer
		ChatTransactions.Reservation crashed = transactions.reserve(shop.user().getId(), shop.analysisId(), null, "Interrupted by a crash");
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before + 1);

		int recovered = recovery.recoverPending();

		assertThat(recovered).isEqualTo(1);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before);
		assertThat(pendingMessages(shop.analysisId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE id = ?", Integer.class, crashed.conversationId())).isZero();
		assertThat(recovery.recoverPending()).isZero(); // idempotent
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before);
	}

	@Test
	void startupKeepsTheEarlierMessagesOfAConversationAQuestionWasInterruptedIn() throws Exception {
		Ready shop = readyShop();
		String first = ask(shop.user(), shop.analysisId(), "A finished question", null);
		UUID conversationId = UUID.fromString(read(first, "$.conversationId"));
		int before = used(shop.user(), "AI_QUERY");
		transactions.reserve(shop.user().getId(), shop.analysisId(), conversationId, "Interrupted follow-up");

		recovery.recoverPending();

		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE conversation_id = ?", Integer.class, conversationId)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE id = ?", Integer.class, conversationId)).isEqualTo(1);
	}

	@Test
	void aFailedAnswerDoesNotConsumeAQuestionAndTheUserCanRetry() throws Exception {
		Ready shop = readyShop();
		GEMINI.failWith(500);
		com.abhiraj.archyra.ai.FakeProviders.GROQ.failWith(500);
		for (int i = 0; i < LIMIT + 2; i++) {
			assertThat(chat(shop.user(), shop.analysisId(), "Failing question " + i, null).getResponse().getStatus()).isEqualTo(503);
		}
		assertThat(used(shop.user(), "AI_QUERY")).isZero(); // five failures, none counted: more than the allowance
		GEMINI.reset();
		com.abhiraj.archyra.ai.FakeProviders.GROQ.reset();

		assertThat(chat(shop.user(), shop.analysisId(), "Now it works", null).getResponse().getStatus()).isEqualTo(200);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(1);
	}
}
