package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Without any provider key the application starts, indexes and refuses chat cleanly, without charging. */
@TestPropertySource(properties = { "archyra.ai.gemini.api-key=", "archyra.ai.groq.api-key=" })
class ChatNoKeysIntegrationTest extends ChatIntegrationSupport {

	@Test
	void indexingNeedsNoKeyButChatAnswers503WithoutChargingOrStoringAnything() throws Exception {
		Ready shop = readyShop(); // the index went READY with no key at all

		MvcResult result = chat(shop.user(), shop.analysisId(), PLACE_QUESTION);

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(result)).isEqualTo("AI_UNAVAILABLE");
		assertThat(result.getResponse().getContentAsString()).contains("not counted");
		assertThat(used(shop.user(), "AI_QUERY")).isZero();
		assertThat(storedMessages(shop.analysisId())).isZero();
		assertThat(conversationRows(shop.analysisId())).isZero();
		assertThat(FakeProviders.GEMINI.requestCount()).isZero();
		assertThat(FakeProviders.GROQ.requestCount()).isZero();
	}

	@Test
	void conversationsStillWorkWithoutKeys() throws Exception {
		Ready shop = readyShop();

		MvcResult created = postAs(shop.user(), "/api/v1/analyses/" + shop.analysisId() + "/conversations");

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
	}
}
