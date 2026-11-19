package com.abhiraj.archyra.ai.chat;

import static com.abhiraj.archyra.ai.FakeProviders.GEMINI;
import static com.abhiraj.archyra.ai.FakeProviders.GROQ;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.ai.ChatIntegrationSupport;

/**
 * A question that nothing in the repository relates to is refused with {@code 422 QUESTION_NOT_ANSWERABLE} after retrieval and
 * before anything is charged, sent or stored. The refusal still takes one token of the rate limit (three a minute here), so
 * refusals cannot be used to hammer retrieval; a question that does reach a provider costs one unit even when the answer is
 * "I can't tell".
 */
@TestPropertySource(properties = { "app.security.rate-limit.ai-chat-per-user-per-minute=4", "archyra.usage.free-tier.ai-query-limit=5" })
class QuestionNotAnswerableIntegrationTest extends ChatIntegrationSupport {

	private static final String OFF_TOPIC = "Write me a poem about autumn";

	@Test
	void anOffTopicQuestionIsRefusedWithFixedTextAndNothingIsSentCountedOrStored() throws Exception {
		Ready shop = readyShop();

		MvcResult refused = chat(shop.user(), shop.analysisId(), OFF_TOPIC, null);

		assertThat(refused.getResponse().getStatus()).isEqualTo(422);
		assertThat(code(refused)).isEqualTo("QUESTION_NOT_ANSWERABLE");
		String body = refused.getResponse().getContentAsString();
		assertThat((String) read(body, "$.message")).isEqualTo("I couldn't find anything in this repository that relates to that question, so nothing was sent to the AI and it wasn't counted. "
				+ "Try asking about a class, a file, or how something works.");
		assertThat(body).doesNotContain("poem").doesNotContain("autumn");
		assertThat(GEMINI.requestCount()).isZero();
		assertThat(GROQ.requestCount()).isZero();
		assertThat(used(shop.user(), "AI_QUERY")).isZero();
		assertThat(storedMessages(shop.analysisId())).isZero();
		assertThat(conversationRows(shop.analysisId())).isZero(); // not even the conversation that would have held it
	}

	@Test
	void aRefusalInsideAConversationLeavesItsMessagesAlone() throws Exception {
		Ready shop = readyShop();
		String first = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);
		String conversationId = read(first, "$.conversationId");
		int messages = storedMessages(shop.analysisId());
		int used = used(shop.user(), "AI_QUERY");
		int calls = GEMINI.requestCount();

		MvcResult refused = chat(shop.user(), shop.analysisId(), OFF_TOPIC, conversationId);

		assertThat(refused.getResponse().getStatus()).isEqualTo(422);
		assertThat(storedMessages(shop.analysisId())).isEqualTo(messages);
		assertThat(conversationRows(shop.analysisId())).isEqualTo(1);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(used);
		assertThat(GEMINI.requestCount()).isEqualTo(calls);
	}

	@Test
	void everyRefusalTakesOneTokenOfTheRateLimitSoTheyCannotHammerRetrieval() throws Exception {
		Ready shop = readyShop();
		for (int i = 0; i < 4; i++) {
			assertThat(chat(shop.user(), shop.analysisId(), OFF_TOPIC + " " + i, null).getResponse().getStatus()).isEqualTo(422);
		}

		MvcResult limited = chat(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(code(limited)).isEqualTo("RATE_LIMITED");
		assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(used(shop.user(), "AI_QUERY")).isZero();
		assertThat(GEMINI.requestCount()).isZero();
	}

	@Test
	void aQuestionThatReachesAProviderCostsOneEvenWhenTheAnswerIsICantTell() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith("I can't tell from the context I was given.");

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((String) read(body, "$.answer")).contains("I can't tell");
		assertThat((java.util.List<?>) read(body, "$.sources")).isEmpty();
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(1);
		assertThat(GEMINI.requestCount()).isEqualTo(1);
	}

	@Test
	void aProviderFailureAfterTheReservationStillRefundsExactlyOnce() throws Exception {
		Ready shop = readyShop();
		GEMINI.failWith(500);
		GROQ.failWith(500);

		MvcResult failed = chat(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat(failed.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(failed)).isEqualTo("AI_UNAVAILABLE");
		assertThat(used(shop.user(), "AI_QUERY")).isZero();
		assertThat(storedMessages(shop.analysisId())).isZero();
	}
}
