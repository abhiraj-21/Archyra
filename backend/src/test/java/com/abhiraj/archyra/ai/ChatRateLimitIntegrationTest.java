package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.user.Plan;

/** Questions per minute per user, for every plan. */
@TestPropertySource(properties = "app.security.rate-limit.ai-chat-per-user-per-minute=3")
class ChatRateLimitIntegrationTest extends ChatIntegrationSupport {

	private void exceedsTheLimitAfterThree(Ready shop) throws Exception {
		for (int i = 1; i <= 3; i++) {
			ask(shop.user(), shop.analysisId(), "Question " + i, null);
		}
		int messages = storedMessages(shop.analysisId());
		int used = used(shop.user(), "AI_QUERY");
		int calls = FakeProviders.GEMINI.requestCount();

		MvcResult limited = chat(shop.user(), shop.analysisId(), "Question 4", null);

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(code(limited)).isEqualTo("RATE_LIMITED");
		assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
		assertThat(storedMessages(shop.analysisId())).isEqualTo(messages); // nothing stored
		assertThat(used(shop.user(), "AI_QUERY")).isEqualTo(used); // nothing charged
		assertThat(FakeProviders.GEMINI.requestCount()).isEqualTo(calls); // no model call
	}

	@Test
	void theFourthQuestionInAMinuteIsRefusedForAFreeUser() throws Exception {
		exceedsTheLimitAfterThree(readyShop());
	}

	@Test
	void theFourthQuestionInAMinuteIsRefusedForAPremiumUserToo() throws Exception {
		exceedsTheLimitAfterThree(readyShop(newUser(Plan.PREMIUM)));
	}

	@Test
	void theLimitIsPerUser() throws Exception {
		Ready busy = readyShop();
		Ready other = readyShop();
		for (int i = 1; i <= 3; i++) {
			ask(busy.user(), busy.analysisId(), "Question " + i, null);
		}
		assertThat(chat(busy.user(), busy.analysisId(), "Question 4", null).getResponse().getStatus()).isEqualTo(429);

		assertThat(chat(other.user(), other.analysisId(), "Someone else's first question", null).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void refusedRequestsDoNotUseUpTheAllowance() throws Exception {
		Ready shop = readyShop();
		for (int i = 0; i < 5; i++) {
			assertThat(chat(shop.user(), shop.analysisId(), "   ").getResponse().getStatus()).isEqualTo(400); // invalid: refused before the limit
		}

		for (int i = 1; i <= 3; i++) {
			assertThat(chat(shop.user(), shop.analysisId(), "Valid question " + i).getResponse().getStatus()).isEqualTo(200);
		}
	}
}
