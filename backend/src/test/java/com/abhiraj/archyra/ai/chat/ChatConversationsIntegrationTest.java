package com.abhiraj.archyra.ai.chat;

import static com.abhiraj.archyra.ai.FakeProviders.GEMINI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.ai.ChatIntegrationSupport;
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/** Conversations: create, list, delete, messages, ownership, limits, one question at a time, and what happens to them with their analysis. */
@TestPropertySource(properties = { "archyra.ai.limits.conversations-per-analysis=3", "archyra.ai.limits.messages-per-conversation=4" })
class ChatConversationsIntegrationTest extends ChatIntegrationSupport {

	private static String conversationsPath(UUID analysisId) {
		return "/api/v1/analyses/" + analysisId + "/conversations";
	}

	private static String messagesPath(String conversationId) {
		return "/api/v1/conversations/" + conversationId + "/messages";
	}

	private String createConversation(User user, UUID analysisId) throws Exception {
		MvcResult created = postAs(user, conversationsPath(analysisId));
		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		return json(created, "$.id");
	}

	// ---- create, list, messages ----------------------------------------------------------------

	@Test
	void anEmptyConversationIsCreatedWithTheDefaultTitleAndNoMessages() throws Exception {
		Ready shop = readyShop();

		MvcResult created = postAs(shop.user(), conversationsPath(shop.analysisId()));

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		assertThat((String) json(created, "$.id")).isNotBlank();
		assertThat((String) json(created, "$.title")).isEqualTo("New conversation");
		assertThat((int) json(created, "$.messageCount")).isZero();
		assertThat(Instant.parse(json(created, "$.createdAt"))).isNotNull();
		assertThat((String) json(created, "$.updatedAt")).isNotBlank();
		assertThat(used(shop.user(), "AI_QUERY")).isZero(); // creating one is free
	}

	@Test
	void theFirstQuestionOfAnEmptyConversationGivesItsTitle() throws Exception {
		Ready shop = readyShop();
		String id = createConversation(shop.user(), shop.analysisId());

		ask(shop.user(), shop.analysisId(), "What does `place` do?", id);

		String list = getAs(shop.user(), conversationsPath(shop.analysisId())).getResponse().getContentAsString();
		assertThat((String) JsonPath.read(list, "$.items[0].title")).isEqualTo("What does `place` do?");
		assertThat((int) JsonPath.read(list, "$.items[0].messageCount")).isEqualTo(2);
	}

	@Test
	void conversationsAreListedNewestActivityFirstAndPaged() throws Exception {
		Ready shop = readyShop();
		String older = createConversation(shop.user(), shop.analysisId());
		String newer = createConversation(shop.user(), shop.analysisId());
		assertThat(ids(getAs(shop.user(), conversationsPath(shop.analysisId())))).containsExactly(newer, older);

		ask(shop.user(), shop.analysisId(), q("Activity in the older one"), older);

		MvcResult list = getAs(shop.user(), conversationsPath(shop.analysisId()));
		assertThat(ids(list)).containsExactly(older, newer);
		assertThat((int) json(list, "$.page")).isZero();
		assertThat((int) json(list, "$.size")).isEqualTo(50);
		assertThat((int) json(list, "$.totalElements")).isEqualTo(2);
		MvcResult second = getAs(shop.user(), conversationsPath(shop.analysisId()) + "?page=1&size=1");
		assertThat(ids(second)).containsExactly(newer);
		assertThat((int) json(second, "$.totalPages")).isEqualTo(2);
	}

	private static List<String> ids(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.items[*].id");
	}

	@Test
	void pagingParametersAreBounded() throws Exception {
		Ready shop = readyShop();
		String conversation = createConversation(shop.user(), shop.analysisId());

		for (String path : List.of(conversationsPath(shop.analysisId()) + "?page=-1", conversationsPath(shop.analysisId()) + "?size=0",
				conversationsPath(shop.analysisId()) + "?size=201", messagesPath(conversation) + "?page=-1", messagesPath(conversation) + "?size=201")) {
			MvcResult result = getAs(shop.user(), path);
			assertThat(result.getResponse().getStatus()).as(path).isEqualTo(400);
			assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
		}
		assertThat(getAs(shop.user(), conversationsPath(shop.analysisId()) + "?size=200").getResponse().getStatus()).isEqualTo(200);
		assertThat(getAs(shop.user(), conversationsPath(shop.analysisId()) + "?size=abc").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void messagesComeOldestFirstWithSourcesAndProviderOnlyOnAnswers() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith(prompt -> "See [" + com.abhiraj.archyra.ai.FakeProviders.itemNumber(prompt, ".service.OrderService#place") + "].");
		String first = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);
		String id = read(first, "$.conversationId");
		ask(shop.user(), shop.analysisId(), q("And the other one?"), id);

		MvcResult messages = getAs(shop.user(), messagesPath(id));

		String body = messages.getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(body, "$.items[*].role")).containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
		assertThat((String) JsonPath.read(body, "$.items[0].content")).isEqualTo(PLACE_QUESTION);
		assertThat((String) JsonPath.read(body, "$.items[1].content")).isEqualTo("See [1].");
		assertThat((String) JsonPath.read(body, "$.items[1].provider")).isEqualTo("gemini");
		assertThat((String) JsonPath.read(body, "$.items[1].providerName")).isEqualTo("Gemini");
		assertThat((List<Map<String, Object>>) JsonPath.read(body, "$.items[1].sources")).hasSize(1);
		assertThat(JsonPath.<Map<String, Object>>read(body, "$.items[0]")).doesNotContainKeys("sources", "provider", "providerName"); // only on answers
		assertThat((boolean) JsonPath.read(body, "$.items[1].cached")).isFalse();
		assertThat((int) JsonPath.read(body, "$.totalElements")).isEqualTo(4);
		MvcResult page = getAs(shop.user(), messagesPath(id) + "?page=1&size=3");
		assertThat((List<String>) JsonPath.read(page.getResponse().getContentAsString(), "$.items[*].role")).containsExactly("ASSISTANT");
	}

	@Test
	void creatingNeedsACompletedAnalysis() throws Exception {
		User owner = newUser();
		UUID running = analyses.save(Analysis.queued(SHOP, owner.getId())).getId();

		MvcResult result = postAs(owner, conversationsPath(running));

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(result)).isEqualTo("ANALYSIS_NOT_COMPLETED");
	}

	// ---- delete and ownership ------------------------------------------------------------------

	@Test
	void deletingAConversationRemovesItAndItsMessages() throws Exception {
		Ready shop = readyShop();
		String id = read(ask(shop.user(), shop.analysisId(), q("To be deleted"), null), "$.conversationId");
		String kept = read(ask(shop.user(), shop.analysisId(), q("To be kept"), null), "$.conversationId");

		assertThat(deleteAs(shop.user(), "/api/v1/conversations/" + id).getResponse().getStatus()).isEqualTo(204);

		assertThat(getAs(shop.user(), messagesPath(id)).getResponse().getStatus()).isEqualTo(404);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE conversation_id = ?::uuid", Integer.class, id)).isZero();
		assertThat(ids(getAs(shop.user(), conversationsPath(shop.analysisId())))).containsExactly(kept);
		assertThat(deleteAs(shop.user(), "/api/v1/conversations/" + id).getResponse().getStatus()).isEqualTo(404); // already gone
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(2); // deleting never gives questions back: the allowance is for life
	}

	@Test
	void everyoneElseGetsNotFoundIncludingAdminsAndTheSameForUnknownOrMalformedIds() throws Exception {
		Ready shop = readyShop();
		String id = read(ask(shop.user(), shop.analysisId(), q("A private question"), null), "$.conversationId");
		User stranger = newUser();
		User admin = userService.save(User.administrator("admin-" + UUID.randomUUID() + "@example.com", "Admin",
				passwordEncoder.encode("irrelevant"), Instant.now()));

		for (User caller : new User[] { stranger, admin }) {
			for (MvcResult result : new MvcResult[] { getAs(caller, messagesPath(id)), deleteAs(caller, "/api/v1/conversations/" + id) }) {
				assertThat(result.getResponse().getStatus()).isEqualTo(404);
				assertThat(code(result)).isEqualTo("CONVERSATION_NOT_FOUND");
			}
			MvcResult list = getAs(caller, conversationsPath(shop.analysisId()));
			assertThat(list.getResponse().getStatus()).isEqualTo(404);
			assertThat(code(list)).isEqualTo("ANALYSIS_NOT_FOUND");
			assertThat(postAs(caller, conversationsPath(shop.analysisId())).getResponse().getStatus()).isEqualTo(404);
			MvcResult asking = chat(caller, shop.analysisId(), q("Let me in"), id);
			assertThat(asking.getResponse().getStatus()).isEqualTo(404);
		}
		for (String unknown : List.of(UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1")) {
			assertThat(getAs(shop.user(), messagesPath(unknown)).getResponse().getStatus()).as(unknown).isEqualTo(404);
			assertThat(deleteAs(shop.user(), "/api/v1/conversations/" + unknown).getResponse().getStatus()).as(unknown).isEqualTo(404);
		}
		assertThat(getAs(shop.user(), messagesPath(id)).getResponse().getStatus()).isEqualTo(200); // the owner still has it
	}

	@Test
	void aConversationOfAnotherAnalysisCannotBeUsed() throws Exception {
		Ready shop = readyShop();
		UUID other = analyzeCompleted(shop.user(), TESTS_AND_MAIN);
		awaitIndexStatus(shop.user(), other, "READY");
		String foreign = read(ask(shop.user(), other, q("A question about the other repository"), null), "$.conversationId");
		int usedBefore = used(shop.user(), "AI_QUERY");

		MvcResult result = chat(shop.user(), shop.analysisId(), PLACE_QUESTION, foreign);

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(code(result)).isEqualTo("CONVERSATION_NOT_FOUND");
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore);
	}

	@Test
	void aMalformedConversationIdInTheBodyIsAValidationError() throws Exception {
		Ready shop = readyShop();

		MvcResult result = chat(shop.user(), shop.analysisId(), PLACE_QUESTION, "not-a-uuid");

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
	}

	// ---- limits --------------------------------------------------------------------------------

	@Test
	void aFullConversationRefusesFurtherQuestionsWithoutACharge() throws Exception {
		Ready shop = readyShop();
		String id = read(ask(shop.user(), shop.analysisId(), q("Question one"), null), "$.conversationId");
		ask(shop.user(), shop.analysisId(), q("Question two"), id); // four messages: the conversation is full
		int usedBefore = used(shop.user(), "AI_QUERY");

		MvcResult result = chat(shop.user(), shop.analysisId(), q("Question three"), id);

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(result)).isEqualTo("CONVERSATION_FULL");
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore);
		assertThat(storedMessages(shop.analysisId())).isEqualTo(4);
		assertThat(chat(shop.user(), shop.analysisId(), q("Question three, elsewhere"), null).getResponse().getStatus()).isEqualTo(200); // a new conversation is fine
	}

	@Test
	void tooManyConversationsAreRefusedWhetherCreatedEmptyOrByAQuestionAndNeverCharged() throws Exception {
		Ready shop = readyShop();
		createConversation(shop.user(), shop.analysisId());
		createConversation(shop.user(), shop.analysisId());
		ask(shop.user(), shop.analysisId(), q("Fills the third slot"), null);
		int usedBefore = used(shop.user(), "AI_QUERY");

		MvcResult empty = postAs(shop.user(), conversationsPath(shop.analysisId()));
		MvcResult byQuestion = chat(shop.user(), shop.analysisId(), q("Would need a fourth"), null);

		assertThat(empty.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(empty)).isEqualTo("TOO_MANY_CONVERSATIONS");
		assertThat(byQuestion.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(byQuestion)).isEqualTo("TOO_MANY_CONVERSATIONS");
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore); // the reservation rolled back with the refusal
		assertThat(conversationRows(shop.analysisId())).isEqualTo(3);
		assertThat(GEMINI.requestCount()).isEqualTo(1); // the refused question never reached the model
		assertThat(deleteAs(shop.user(), "/api/v1/conversations/" + ids(getAs(shop.user(), conversationsPath(shop.analysisId()))).getFirst())
				.getResponse().getStatus()).isEqualTo(204);
		assertThat(postAs(shop.user(), conversationsPath(shop.analysisId())).getResponse().getStatus()).isEqualTo(201); // room again
	}

	@Test
	void aBlankOrTooLongMessageIsRejectedBeforeAnythingHappens() throws Exception {
		Ready shop = readyShop();

		for (String message : new String[] { "", "   \n ", "x".repeat(2001) }) {
			MvcResult result = chat(shop.user(), shop.analysisId(), message);
			assertThat(result.getResponse().getStatus()).isEqualTo(400);
			assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
		}
		assertThat(chat(shop.user(), shop.analysisId(), q("").concat("x".repeat(2000 - q("").length()))).getResponse().getStatus()).isEqualTo(200); // exactly the limit is fine
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(1);
	}

	// ---- one question at a time ----------------------------------------------------------------

	@Test
	void aConversationAnswersOneQuestionAtATimeAndAPendingQuestionIsInvisible() throws Exception {
		Ready shop = readyShop();
		String id = read(ask(shop.user(), shop.analysisId(), q("An earlier question"), null), "$.conversationId");
		int usedBefore = used(shop.user(), "AI_QUERY");
		CountDownLatch inModel = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		GEMINI.answerWith(prompt -> {
			inModel.countDown();
			try {
				release.await(60, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return "The slow answer.";
		});
		ExecutorService threads = Executors.newSingleThreadExecutor();
		try {
			Future<MvcResult> slow = threads.submit(() -> chat(shop.user(), shop.analysisId(), q("A slow question"), id));
			assertThat(inModel.await(30, TimeUnit.SECONDS)).isTrue();
			assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore + 1); // reserved before the model call

			MvcResult second = chat(shop.user(), shop.analysisId(), q("Asked at the same time"), id);
			MvcResult deletion = deleteAs(shop.user(), "/api/v1/conversations/" + id);
			MvcResult messages = getAs(shop.user(), messagesPath(id));

			assertThat(second.getResponse().getStatus()).isEqualTo(409);
			assertThat(code(second)).isEqualTo("CONVERSATION_BUSY");
			assertThat(deletion.getResponse().getStatus()).isEqualTo(409);
			assertThat(code(deletion)).isEqualTo("CONVERSATION_BUSY");
			assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore + 1); // the refused one cost nothing
			assertThat((List<String>) JsonPath.read(messages.getResponse().getContentAsString(), "$.items[*].content"))
					.containsExactly(q("An earlier question"), "default answer"); // the pending question is not listed

			release.countDown();
			assertThat(slow.get(60, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
		}
		finally {
			release.countDown();
			threads.shutdownNow();
		}
		GEMINI.answerWith("Free again.");
		assertThat(getAs(shop.user(), messagesPath(id)).getResponse().getStatus()).isEqualTo(200);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore + 1);
		// the conversation is not busy any more: a fifth message is refused for being full, not for being busy
		assertThat(code(chat(shop.user(), shop.analysisId(), q("Is it free?"), id))).isEqualTo("CONVERSATION_FULL");
	}

	// ---- with their analysis -------------------------------------------------------------------

	@Test
	void deletingAnAnalysisRemovesItsConversationsAndMessages() throws Exception {
		Ready shop = readyShop();
		Ready other = readyShop(shop.user());
		ask(shop.user(), shop.analysisId(), q("Doomed one"), null);
		ask(shop.user(), shop.analysisId(), q("Doomed two"), null);
		ask(other.user(), other.analysisId(), q("Kept"), null);

		assertThat(deleteAs(shop.user(), "/api/v1/analyses/" + shop.analysisId()).getResponse().getStatus()).isEqualTo(204);

		assertThat(conversationRows(shop.analysisId())).isZero();
		assertThat(storedMessages(shop.analysisId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE conversation_id NOT IN (SELECT id FROM conversations)", Integer.class)).isZero();
		assertThat(conversationRows(other.analysisId())).isEqualTo(1);
		assertThat(storedMessages(other.analysisId())).isEqualTo(2);
	}

	@Test
	void aCompletedRerunRemovesTheReplacedAnalysesConversations() throws Exception {
		Ready shop = readyShop();
		ask(shop.user(), shop.analysisId(), q("Asked about the old analysis"), null);

		MvcResult rerun = postAs(shop.user(), "/api/v1/analyses/" + shop.analysisId() + "/reanalyze");
		UUID replacement = UUID.fromString(json(rerun, "$.analysisId"));
		await().atMost(Duration.ofSeconds(120)).until(() -> analyses.findById(shop.analysisId()).isEmpty()
				&& analyses.findById(replacement).map(Analysis::isFinished).orElse(false));

		assertThat(conversationRows(shop.analysisId())).isZero();
		assertThat(storedMessages(shop.analysisId())).isZero();
		assertThat(conversationRows(replacement)).isZero(); // the new analysis starts with none
	}

	@Test
	void deletingAnAnalysisWhileAQuestionIsBeingAnsweredGivesItsUnitBack() throws Exception {
		Ready shop = readyShop();
		int usedBefore = used(shop.user(), "AI_QUERY");
		CountDownLatch inModel = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		GEMINI.answerWith(prompt -> {
			inModel.countDown();
			try {
				release.await(60, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return "Too late.";
		});
		ExecutorService threads = Executors.newSingleThreadExecutor();
		try {
			Future<Object> pending = threads.submit(() -> {
				try {
					return chat(shop.user(), shop.analysisId(), q("Overtaken by a delete"), null).getResponse().getStatus();
				}
				catch (Exception ex) {
					return ex; // the answer has nowhere to go: the request ends in an error, not a hang
				}
			});
			assertThat(inModel.await(30, TimeUnit.SECONDS)).isTrue();
			assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore + 1);

			assertThat(deleteAs(shop.user(), "/api/v1/analyses/" + shop.analysisId()).getResponse().getStatus()).isEqualTo(204);

			assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore); // the unit came back with the delete
			release.countDown();
			Object outcome = pending.get(60, TimeUnit.SECONDS);
			assertThat(outcome).isNotEqualTo(200);
		}
		finally {
			release.countDown();
			threads.shutdownNow();
		}
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore); // and the late failure did not return it a second time
		assertThat(conversationRows(shop.analysisId())).isZero();
		assertThat(storedMessages(shop.analysisId())).isZero();
	}
}
