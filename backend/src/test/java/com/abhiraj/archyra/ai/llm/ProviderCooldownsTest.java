package com.abhiraj.archyra.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ProviderCooldownsTest {

	private final ProviderKit.Chain chain = ProviderKit.create().setting("x", "unused", "1")
			.set("archyra.ai.providers.cooldown-default", "30s").set("archyra.ai.providers.cooldown-max", "60s")
			.set("archyra.ai.providers.cooldown-misconfigured", "10m").build();
	private final MovableClock clock = chain.clock();
	private final ProviderCooldowns cooldowns = chain.cooldowns();

	@Test
	void aProviderIsFreeUntilItIsRateLimited() {
		assertThat(cooldowns.isCoolingDown("gemini")).isFalse();
		assertThat(cooldowns.coolingUntil("gemini")).isNull();
	}

	@Test
	void withoutRetryAfterTheDefaultCoolDownApplies() {
		cooldowns.rateLimited("gemini", null);

		clock.advance(Duration.ofSeconds(29));
		assertThat(cooldowns.isCoolingDown("gemini")).isTrue();
		clock.advance(Duration.ofSeconds(2));
		assertThat(cooldowns.isCoolingDown("gemini")).isFalse();
	}

	@Test
	void retryAfterIsHonouredButCappedAtTheMaximum() {
		cooldowns.rateLimited("groq", Duration.ofSeconds(5));
		cooldowns.rateLimited("gemini", Duration.ofHours(1));

		clock.advance(Duration.ofSeconds(6));
		assertThat(cooldowns.isCoolingDown("groq")).isFalse();
		clock.advance(Duration.ofSeconds(50));
		assertThat(cooldowns.isCoolingDown("gemini")).isTrue(); // 56 s into a 60 s cap
		clock.advance(Duration.ofSeconds(5));
		assertThat(cooldowns.isCoolingDown("gemini")).isFalse();
	}

	@Test
	void theProvidersCoolDownIndependently() {
		cooldowns.rateLimited("gemini", null);

		assertThat(cooldowns.isCoolingDown("gemini")).isTrue();
		assertThat(cooldowns.isCoolingDown("groq")).isFalse();
	}

	@Test
	void repeatedRateLimitsEscalateUpToTheCapAndASuccessStartsOver() {
		// default 30 s, cap 60 s: 30, 60, 60 ...
		assertThat(cooldowns.rateLimited("groq", null)).isEqualTo(Duration.ofSeconds(30));
		clock.advance(Duration.ofSeconds(31));
		assertThat(cooldowns.rateLimited("groq", null)).isEqualTo(Duration.ofSeconds(60));
		clock.advance(Duration.ofSeconds(61));
		assertThat(cooldowns.rateLimited("groq", null)).isEqualTo(Duration.ofSeconds(60));

		cooldowns.succeeded("groq");
		assertThat(cooldowns.isCoolingDown("groq")).isFalse();
		assertThat(cooldowns.rateLimited("groq", null)).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void aLaterRateLimitNeverWaitsLessThanTheRetryAfterItWasGiven() {
		cooldowns.rateLimited("groq", Duration.ofSeconds(5));
		clock.advance(Duration.ofSeconds(6));

		// second strike: backoff would be 60 s (2 x 30), the provider asked for 45 s: the longer one wins
		assertThat(cooldowns.rateLimited("groq", Duration.ofSeconds(45))).isEqualTo(Duration.ofSeconds(60));
		clock.advance(Duration.ofSeconds(61));
		assertThat(cooldowns.rateLimited("groq", Duration.ofSeconds(50))).isEqualTo(Duration.ofSeconds(60)); // capped
	}

	@Test
	void theEscalationIsForgottenAfterALongQuietTime() {
		cooldowns.rateLimited("groq", null);
		clock.advance(Duration.ofSeconds(31));
		cooldowns.rateLimited("groq", null); // 60 s now
		clock.advance(Duration.ofMinutes(10)); // more than twice the cap without trouble

		assertThat(cooldowns.rateLimited("groq", null)).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void aMisconfiguredProviderIsLeftAloneForTheLongCoolDown() {
		assertThat(cooldowns.misconfigured("zai")).isEqualTo(Duration.ofMinutes(10));

		clock.advance(Duration.ofMinutes(9));
		assertThat(cooldowns.isCoolingDown("zai")).isTrue();
		clock.advance(Duration.ofMinutes(2));
		assertThat(cooldowns.isCoolingDown("zai")).isFalse();
	}
}
