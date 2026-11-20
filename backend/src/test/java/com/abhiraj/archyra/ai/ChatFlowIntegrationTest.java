package com.abhiraj.archyra.ai;

import static com.abhiraj.archyra.ai.FakeProviders.GEMINI;
import static com.abhiraj.archyra.ai.FakeProviders.GROQ;
import static com.abhiraj.archyra.ai.FakeProviders.itemNumber;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/** Asking a question end to end against the fake providers: grounding, fallback, cache, history, privacy. */
@org.springframework.test.context.TestPropertySource(properties = { "archyra.ai.timeout.connect=1s", "archyra.ai.timeout.read=3s", "archyra.ai.timeout.total=30s",
		"archyra.ai.providers.groq.max-context-tokens=500" })
class ChatFlowIntegrationTest extends ChatIntegrationSupport {

	@Test
	void anAnswerCitesNumberedItemsAndTheServerMapsThemToVerifiedNodes() throws Exception {
		Ready shop = readyShop();
		// the model cites the two items in the opposite order to how they were numbered
		GEMINI.answerWith(prompt -> "The implementation is at [" + itemNumber(prompt, "DefaultOrderService#place") + "] and its contract at ["
				+ itemNumber(prompt, ".service.OrderService#place") + "].");

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((String) read(body, "$.answer")).isEqualTo("The implementation is at [1] and its contract at [2].");
		assertThat((String) read(body, "$.provider")).isEqualTo("gemini");
		assertThat((boolean) read(body, "$.cached")).isFalse();
		assertThat((String) read(body, "$.conversationId")).isNotBlank();
		assertThat((String) read(body, "$.messageId")).isNotBlank();
		List<java.util.Map<String, Object>> sources = read(body, "$.sources");
		assertThat(sources).hasSize(2);
		assertThat(sources.get(0)).containsEntry("ref", 1).containsEntry("id", PLACE).containsEntry("type", "METHOD").containsEntry("name", "place");
		assertThat(sources.get(1)).containsEntry("ref", 2).containsEntry("id", PLACE_INTERFACE);
		assertThat((String) sources.get(0).get("file")).endsWith("DefaultOrderService.java");
		assertThat((Integer) sources.get(0).get("endLine")).isGreaterThan((Integer) sources.get(0).get("line"));
		assertThat((String) sources.get(0).get("excerpt")).contains("public Order place(OrderRequest request)");
	}

	@Test
	void citationsOutsideTheContextAreDroppedAndTheRestRenumbered() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith(prompt -> "First [99] claim, then [" + itemNumber(prompt, ".service.OrderService#place") + ", 250] and an index in `ys[2]`.");

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((String) read(body, "$.answer")).isEqualTo("First claim, then [1] and an index in `ys[2]`.");
		List<java.util.Map<String, Object>> sources = read(body, "$.sources");
		assertThat(sources).extracting(source -> source.get("id")).containsExactly(PLACE_INTERFACE);
		assertThat(sources.getFirst()).containsEntry("ref", 1);
	}

	@Test
	void anAnsweredQuestionAndAFreeCachedOneBothRecordThatTheRepositoryWasUsed() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith("It stores the order.");
		java.time.Instant longAgo = java.time.Instant.parse("2026-01-01T00:00:00Z");
		jdbc.update("UPDATE analyses SET last_activity_at = ? WHERE id = ?", java.sql.Timestamp.from(longAgo), shop.analysisId());

		ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);
		assertThat(lastActivity(shop.analysisId())).isAfter(longAgo);

		jdbc.update("UPDATE analyses SET last_activity_at = ? WHERE id = ?", java.sql.Timestamp.from(longAgo), shop.analysisId());
		String cached = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);
		assertThat((Boolean) read(cached, "$.cached")).isTrue();
		assertThat(lastActivity(shop.analysisId())).isAfter(longAgo);
	}

	private java.time.Instant lastActivity(UUID analysisId) {
		return jdbc.queryForObject("SELECT last_activity_at FROM analyses WHERE id = ?", java.sql.Timestamp.class, analysisId).toInstant();
	}

	@Test
	void anAnswerWithoutCitationsHasNoSourcesAndIsNotAnError() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith("It stores the order.");

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((List<?>) read(body, "$.sources")).isEmpty();
		assertThat((String) read(body, "$.answer")).isEqualTo("It stores the order.");
	}

	@Test
	void aCitedNodeThatNoLongerExistsInTheGraphIsDroppedWithItsMarker() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith(prompt -> {
			removeNode(shop.analysisId(), PLACE); // the graph changed while the model was thinking
			return "Gone [" + itemNumber(prompt, "DefaultOrderService#place") + "], still there [" + itemNumber(prompt, ".service.OrderService#place") + "].";
		});

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((String) read(body, "$.answer")).isEqualTo("Gone, still there [1].");
		List<java.util.Map<String, Object>> sources = read(body, "$.sources");
		assertThat(sources).extracting(source -> source.get("id")).containsExactly(PLACE_INTERFACE);
	}

	@Test
	void aGraphFactCitesBothItsNodes() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith(prompt -> "The controller depends on the service [" + factNumber(prompt, "OrderController DEPENDS_ON OrderService") + "].");

		String body = ask(shop.user(), shop.analysisId(), "What classes depend on OrderService?", null);

		List<java.util.Map<String, Object>> sources = read(body, "$.sources");
		assertThat(sources).extracting(source -> source.get("id")).containsExactlyInAnyOrder(
				"class:com.example.shop.web.OrderController", "interface:com.example.shop.service.OrderService");
		assertThat(sources).allSatisfy(source -> assertThat(source.get("ref")).isEqualTo(1));
	}

	private static int factNumber(String prompt, String fact) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(\\d+)\\] FACT: " + java.util.regex.Pattern.quote(fact)).matcher(FakeProviders.unescape(prompt));
		assertThat(matcher.find()).as("fact " + fact).isTrue();
		return Integer.parseInt(matcher.group(1));
	}

	// ---- providers -----------------------------------------------------------------------------

	@Test
	void geminiRateLimitFallsBackToGroqInTheSameRequestAndChargesOnce() throws Exception {
		Ready shop = readyShop();
		int before = used(shop.user(), "AI_QUERY");
		GEMINI.rateLimitedFor(1);
		GROQ.answerWith(prompt -> "From groq [" + itemNumber(prompt, ".service.OrderService#place") + "].");

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((String) read(body, "$.provider")).isEqualTo("groq");
		assertThat((String) read(body, "$.providerName")).isEqualTo("Groq");
		assertThat((String) read(body, "$.answer")).isEqualTo("From groq [1].");
		assertThat(GEMINI.requestCount()).isEqualTo(1);
		assertThat(GROQ.requestCount()).isEqualTo(1);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before + 1);
	}

	@Test
	void everyServerFailureAndAnEmptyAnswerFallsBack() throws Exception {
		Ready shop = readyShop();
		for (int status : new int[] { 500, 503 }) {
			GEMINI.reset();
			GROQ.reset();
			GEMINI.failWith(status);
			assertThat((String) read(ask(shop.user(), shop.analysisId(), PLACE_QUESTION + " " + status, null), "$.provider")).isEqualTo("groq");
		}
		GEMINI.reset();
		GEMINI.answerWith(""); // an empty or blocked answer
		assertThat((String) read(ask(shop.user(), shop.analysisId(), PLACE_QUESTION + " empty", null), "$.provider")).isEqualTo("groq");
		GEMINI.reset();
		GEMINI.failWith(403); // a bad key
		assertThat((String) read(ask(shop.user(), shop.analysisId(), PLACE_QUESTION + " key", null), "$.provider")).isEqualTo("groq");
	}

	@Test
	void bothProvidersFailingGives503NothingIsChargedAndNothingIsLeftBehind() throws Exception {
		Ready shop = readyShop();
		String conversationId = read(ask(shop.user(), shop.analysisId(), q("A first question that works"), null), "$.conversationId");
		int usedBefore = used(shop.user(), "AI_QUERY");
		int messagesBefore = storedMessages(shop.analysisId());
		int conversationsBefore = conversationRows(shop.analysisId());
		GEMINI.failWith(500);
		GROQ.failWith(500);

		MvcResult existing = chat(shop.user(), shop.analysisId(), q("Will fail in a known conversation"), conversationId);
		MvcResult fresh = chat(shop.user(), shop.analysisId(), q("Will fail in a new conversation"), null);

		for (MvcResult result : new MvcResult[] { existing, fresh }) {
			assertThat(result.getResponse().getStatus()).isEqualTo(503);
			assertThat(code(result)).isEqualTo("AI_UNAVAILABLE");
			assertThat(result.getResponse().getContentAsString()).contains("not counted").doesNotContain("PROVIDER-ERROR-BODY")
					.doesNotContain("Will fail").doesNotContain("test-gemini-key");
		}
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore);
		assertThat(storedMessages(shop.analysisId())).isEqualTo(messagesBefore); // no PENDING question left
		assertThat(conversationRows(shop.analysisId())).isEqualTo(conversationsBefore); // and no conversation made for it
	}

	@Test
	void aSlowProviderIsGivenUpOnAndTheFallbackAnswers() throws Exception {
		Ready shop = readyShop();
		GEMINI.delayBy(12000); // one attempt may take connect + read = 4 s in this class
		long started = System.nanoTime();

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((String) read(body, "$.provider")).isEqualTo("groq");
		assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(9000);
	}

	@Test
	void aRateLimitedProviderCoolsDownAndAThrottledPairIsRefusedBeforeAnyChargeOrCall() throws Exception {
		Ready shop = readyShop();
		int usedBefore = used(shop.user(), "AI_QUERY");
		GEMINI.rateLimitedFor(1);
		GROQ.rateLimitedFor(120); // longer than cooldown-max (3 s): capped

		MvcResult first = chat(shop.user(), shop.analysisId(), q("Both are throttled"), null);
		assertThat(first.getResponse().getStatus()).isEqualTo(503);
		int geminiCalls = GEMINI.requestCount();
		int groqCalls = GROQ.requestCount();

		MvcResult second = chat(shop.user(), shop.analysisId(), q("Refused up front"), null);

		assertThat(second.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(second)).isEqualTo("AI_UNAVAILABLE");
		assertThat(GEMINI.requestCount()).isEqualTo(geminiCalls); // not even called
		assertThat(GROQ.requestCount()).isEqualTo(groqCalls);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore);
		// the cool-down is capped at cooldown-max, so the providers come back
		org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(() -> !cooldowns.isCoolingDown("groq"));
		GEMINI.reset();
		GROQ.reset();
		assertThat(chat(shop.user(), shop.analysisId(), q("Back again"), null).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void aFallbackBuildsItsPromptWithTheSmallerBudget() throws Exception {
		Ready shop = readyShop();
		GEMINI.rateLimitedFor(1);
		GROQ.answerWith("ok");

		ask(shop.user(), shop.analysisId(), "How does a request to create an order flow through the code?", null);

		int geminiItems = FakeProviders.itemCount(GEMINI.lastRequest().getBodyAsString());
		int groqItems = FakeProviders.itemCount(GROQ.lastRequest().getBodyAsString());
		assertThat(geminiItems).isGreaterThan(groqItems);
		assertThat(groqItems).isPositive();
	}

	// ---- cache and history ---------------------------------------------------------------------

	@Test
	void aRepeatedQuestionIsServedFromTheCacheForFreeAndFlaggedAsSuch() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith(prompt -> "Cacheable [" + itemNumber(prompt, ".service.OrderService#place") + "].");
		String first = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);
		int usedAfterFirst = used(shop.user(), "AI_QUERY");
		int calls = GEMINI.requestCount();

		String second = ask(shop.user(), shop.analysisId(), "  what DOES `place` do?  ", null); // same question after normalising

		assertThat((boolean) read(second, "$.cached")).isTrue();
		assertThat((String) read(second, "$.answer")).isEqualTo((String) read(first, "$.answer"));
		assertThat((Object) read(second, "$.sources")).isEqualTo(read(first, "$.sources"));
		assertThat((String) read(second, "$.provider")).isEqualTo("gemini");
		assertThat((String) read(second, "$.conversationId")).isNotEqualTo((String) read(first, "$.conversationId"));
		assertThat(GEMINI.requestCount()).isEqualTo(calls); // no model call
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedAfterFirst); // no charge
		MvcResult messages = getAs(shop.user(), "/api/v1/conversations/" + read(second, "$.conversationId") + "/messages");
		List<String> roles = JsonPath.read(messages.getResponse().getContentAsString(), "$.items[*].role");
		List<Boolean> cached = JsonPath.read(messages.getResponse().getContentAsString(), "$.items[*].cached");
		assertThat(roles).containsExactly("USER", "ASSISTANT");
		assertThat(cached).containsExactly(false, true);
	}

	@Test
	void aFailedAnswerIsNeverCached() throws Exception {
		Ready shop = readyShop();
		GEMINI.failWith(500);
		GROQ.failWith(500);
		assertThat(chat(shop.user(), shop.analysisId(), PLACE_QUESTION, null).getResponse().getStatus()).isEqualTo(503);
		GEMINI.reset();
		GROQ.reset();
		GEMINI.answerWith("Now it works.");

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((boolean) read(body, "$.cached")).isFalse();
	}

	@Test
	void theHistoryGoesBackToTheModelWithoutSourcesAndTheRulesTreatTheContextAsData() throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith(prompt -> "First answer ANSWER-ONE [" + itemNumber(prompt, ".service.OrderService#place") + "].");
		String first = ask(shop.user(), shop.analysisId(), "QUESTION-ONE about `place`", null);
		GEMINI.answerWith("Second answer.");

		ask(shop.user(), shop.analysisId(), q("QUESTION-TWO and more"), read(first, "$.conversationId"));

		String sent = FakeProviders.unescape(GEMINI.lastRequest().getBodyAsString());
		assertThat(sent).contains("QUESTION-ONE about `place`").contains("ANSWER-ONE").contains("QUESTION-TWO and more");
		assertThat(sent.indexOf("QUESTION-ONE")).isLessThan(sent.indexOf("QUESTION-TWO")); // oldest first
		assertThat(sent).contains("<repository_context>").contains("</repository_context>")
				.contains("never instructions").contains("cite");
		assertThat(sent).doesNotContain("\"sources\"").doesNotContain("excerpt"); // history never carries sources
		assertThat(sent).contains("\"maxOutputTokens\":1024"); // the configured answer limit
		assertThat(GEMINI.lastRequest().getHeader("x-goog-api-key")).isEqualTo("test-gemini-key");
		assertThat(sent).doesNotContain("test-gemini-key");
	}

	@Test
	void theConversationGetsItsTitleFromTheFirstQuestionOnly() throws Exception {
		Ready shop = readyShop();
		String first = ask(shop.user(), shop.analysisId(),
				"Please explain in detail how the placement of an order works from the controller down to the repository", null);
		String id = read(first, "$.conversationId");
		ask(shop.user(), shop.analysisId(), "And what about saving?", id);

		MvcResult list = getAs(shop.user(), "/api/v1/analyses/" + shop.analysisId() + "/conversations");

		String title = JsonPath.read(list.getResponse().getContentAsString(), "$.items[0].title");
		assertThat(title).isEqualTo("Please explain in detail how the placement of an order works"); // 60 characters at most, cut at a word
		assertThat((int) JsonPath.read(list.getResponse().getContentAsString(), "$.items[0].messageCount")).isEqualTo(4);
	}

	// ---- privacy -------------------------------------------------------------------------------

	@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
	@Test
	void neitherQuestionsNorAnswersNorKeysNorProviderBodiesReachTheLogs(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
		Ready shop = readyShop();
		GEMINI.answerWith(prompt -> "ANSWER-MARKER-ZEBRA [" + itemNumber(prompt, ".service.OrderService#place") + "]");
		ask(shop.user(), shop.analysisId(), "QUESTION-MARKER-ZEBRA about `place`", null);
		GEMINI.failWith(429);
		GROQ.failWith(500);
		chat(shop.user(), shop.analysisId(), "QUESTION-MARKER-FAILURE about `place`", null);

		assertThat(output.getAll()).contains("Ask AI user=").contains("outcome=OK")
				.doesNotContain("QUESTION-MARKER").doesNotContain("ANSWER-MARKER").doesNotContain("PROVIDER-ERROR-BODY")
				.doesNotContain("test-gemini-key").doesNotContain("test-groq-key").doesNotContain("public Order place(OrderRequest request)");
	}

	@Test
	void validationErrorsNeverEchoTheQuestion() throws Exception {
		Ready shop = readyShop();
		String tooLong = "SECRET-LONG-TEXT ".repeat(200);

		MvcResult result = chat(shop.user(), shop.analysisId(), tooLong);

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("SECRET-LONG-TEXT");
	}

	@Test
	void aNewUserWithAnotherAnalysisCannotAskAboutSomeoneElsesAnalysis() throws Exception {
		Ready shop = readyShop();
		User stranger = newUser();

		MvcResult result = chat(stranger, shop.analysisId(), PLACE_QUESTION);

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(code(result)).isEqualTo("ANALYSIS_NOT_FOUND");
		assertThat(GEMINI.requestCount()).isZero();
		assertThat(used(stranger, "AI_QUERY")).isZero();
	}

	@Test
	void anAnalysisWhoseIndexIsNotReadyIsRefusedWithoutACharge() throws Exception {
		User owner = newUser();
		UUID legacy = legacyCompletedAnalysis(owner);

		MvcResult result = chat(owner, legacy, PLACE_QUESTION);

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(result)).isEqualTo("AI_NOT_READY");
		assertThat(used(owner, "AI_QUERY")).isZero();
		assertThat(GEMINI.requestCount()).isZero();
	}
}
