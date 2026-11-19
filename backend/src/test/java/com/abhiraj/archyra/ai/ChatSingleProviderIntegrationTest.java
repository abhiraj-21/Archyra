package com.abhiraj.archyra.ai;

import static com.abhiraj.archyra.ai.FakeProviders.GEMINI;
import static com.abhiraj.archyra.ai.FakeProviders.GROQ;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** With only one key there is no fallback: Groq alone answers, and when it is throttled there is nothing to fall back to. */
@TestPropertySource(properties = "archyra.ai.gemini.api-key=")
class ChatSingleProviderIntegrationTest extends ChatIntegrationSupport {

	@Test
	void theOnlyConfiguredProviderAnswersAndTheOtherIsNeverCalled() throws Exception {
		Ready shop = readyShop();
		GROQ.answerWith("Only groq.");

		String body = ask(shop.user(), shop.analysisId(), PLACE_QUESTION, null);

		assertThat((String) read(body, "$.provider")).isEqualTo("GROQ");
		assertThat(GEMINI.requestCount()).isZero();
	}

	@Test
	void whenItIsThrottledThereIsNoFallbackAndTheNextQuestionIsRefusedBeforeAnyChargeOrCall() throws Exception {
		Ready shop = readyShop();
		GROQ.rateLimitedFor(1);

		MvcResult first = chat(shop.user(), shop.analysisId(), "Throttled", null);
		assertThat(first.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(first)).isEqualTo("AI_UNAVAILABLE");
		int calls = GROQ.requestCount();

		MvcResult second = chat(shop.user(), shop.analysisId(), "Refused up front", null);

		assertThat(second.getResponse().getStatus()).isEqualTo(503);
		assertThat(GROQ.requestCount()).isEqualTo(calls);
		assertThat(GEMINI.requestCount()).isZero();
		assertThat(used(shop.user(), "AI_QUERY")).isZero();
		assertThat(storedMessages(shop.analysisId())).isZero();
	}
}
