package com.abhiraj.archyra.ai;

import static com.abhiraj.archyra.ai.FakeProviders.GEMINI;
import static com.abhiraj.archyra.ai.FakeProviders.GROQ;
import static com.abhiraj.archyra.ai.FakeProviders.OPENROUTER;
import static com.abhiraj.archyra.ai.FakeProviders.ZAI;
import static com.abhiraj.archyra.ai.FakeProviders.itemNumber;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * The provider chain through the whole chat flow with four providers (Gemini, Groq, OpenRouter, Z AI): the fallback inside one
 * request, one charge, the refund when every provider fails, the pre-flight refusal when all of them cool down, and the
 * grounding of a third provider's answer.
 */
@TestPropertySource(properties = { "archyra.ai.providers.openrouter.api-key=test-openrouter-key", "archyra.ai.providers.zai.api-key=test-zai-key" })
class ChatProviderChainIntegrationTest extends ChatIntegrationSupport {

	@Test
	void aQuestionFallsThroughThreeProvidersAndIsGroundedAndChargedOnce() throws Exception {
		Ready shop = readyShop();
		int before = used(shop.user(), "AI_QUERY");
		GEMINI.failWith(500);
		GROQ.rateLimitedFor(1);
		OPENROUTER.answerWith(prompt -> "<think>I should cite [77] first</think>The implementation is at ["
				+ itemNumber(prompt, "DefaultOrderService#place") + "], and a made-up claim [99].");

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((String) read(body, "$.provider")).isEqualTo("openrouter");
		assertThat((String) read(body, "$.providerName")).isEqualTo("OpenRouter");
		String answer = read(body, "$.answer");
		assertThat(answer).doesNotContain("think").doesNotContain("[77]").doesNotContain("[99]").contains("[1]"); // thoughts gone, invented markers dropped
		List<Map<String, Object>> sources = read(body, "$.sources");
		assertThat(sources).hasSize(1); // verified in Neo4j like any other provider's answer
		assertThat(sources.getFirst().get("id")).isEqualTo(PLACE);
		assertThat(List.of(GEMINI.requestCount(), GROQ.requestCount(), OPENROUTER.requestCount(), ZAI.requestCount())).containsExactly(1, 1, 1, 0);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(before + 1);

		// the stored answer remembers who answered, by id and by name
		String conversation = read(body, "$.conversationId");
		MvcResult messages = getAs(shop.user(), "/api/v1/conversations/" + conversation + "/messages");
		String listed = messages.getResponse().getContentAsString();
		assertThat((String) JsonPath.read(listed, "$.items[1].provider")).isEqualTo("openrouter");
		assertThat((String) JsonPath.read(listed, "$.items[1].providerName")).isEqualTo("OpenRouter");
	}

	@Test
	void aCachedAnswerStillNamesTheProviderThatGaveIt() throws Exception {
		Ready shop = readyShop();
		GEMINI.failWith(500);
		GROQ.failWith(500);
		OPENROUTER.answerWith(prompt -> "Cacheable [" + itemNumber(prompt, "DefaultOrderService#place") + "].");
		ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		String second = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((boolean) read(second, "$.cached")).isTrue();
		assertThat((String) read(second, "$.provider")).isEqualTo("openrouter");
		assertThat((String) read(second, "$.providerName")).isEqualTo("OpenRouter");
		assertThat(OPENROUTER.requestCount()).isEqualTo(1);
	}

	@Test
	void everyProviderFailingGives503AndTheUnitIsRefundedExactlyOnce() throws Exception {
		Ready shop = readyShop();
		int usedBefore = used(shop.user(), "AI_QUERY");
		GEMINI.failWith(500);
		GROQ.failWith(500);
		OPENROUTER.failWith(502);
		ZAI.failWith(503);

		MvcResult result = chat(shop.user(), shop.analysisId(), "Will fail everywhere", null);

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(result)).isEqualTo("AI_UNAVAILABLE");
		assertThat(List.of(GEMINI.requestCount(), GROQ.requestCount(), OPENROUTER.requestCount(), ZAI.requestCount())).containsExactly(1, 1, 1, 1);
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedBefore); // charged before the calls, returned once after them
		assertThat(storedMessages(shop.analysisId())).isZero();
		assertThat(conversationRows(shop.analysisId())).isZero();
	}

	@Test
	void whenEveryProviderCoolsDownTheNextQuestionIsRefusedBeforeAnythingIsCharged() throws Exception {
		Ready shop = readyShop();
		GEMINI.rateLimitedFor(30);
		GROQ.rateLimitedFor(30);
		OPENROUTER.rateLimitedFor(30);
		ZAI.rateLimitedFor(30);
		MvcResult first = chat(shop.user(), shop.analysisId(), "First, all of them answer 429", null);
		assertThat(first.getResponse().getStatus()).isEqualTo(503);
		int calls = GEMINI.requestCount() + GROQ.requestCount() + OPENROUTER.requestCount() + ZAI.requestCount();
		assertThat(calls).isEqualTo(4);
		int usedAfterFirst = used(shop.user(), "AI_QUERY");

		MvcResult second = chat(shop.user(), shop.analysisId(), "Second, nobody is usable", null);

		assertThat(second.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(second)).isEqualTo("AI_UNAVAILABLE");
		assertThat(GEMINI.requestCount() + GROQ.requestCount() + OPENROUTER.requestCount() + ZAI.requestCount()).isEqualTo(calls); // nobody was called
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(usedAfterFirst);
		assertThat(storedMessages(shop.analysisId())).isZero(); // refused in the pre-flight check: not even a PENDING row was written
	}
}
