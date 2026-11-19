package com.abhiraj.archyra.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ai.AiProperties;

class ProviderCooldownsTest {

	/** A clock the test moves by hand. */
	private static final class MovableClock extends Clock {
		private Instant now = Instant.parse("2026-11-19T10:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final MovableClock clock = new MovableClock();
	private final ProviderCooldowns cooldowns = new ProviderCooldowns(new AiProperties(null, null, null,
			new AiProperties.Provider(Duration.ofSeconds(30), Duration.ofSeconds(60)), null, null, null, null, null, null, null, null), clock);

	@Test
	void aProviderIsFreeUntilItIsRateLimited() {
		assertThat(cooldowns.isCoolingDown(ProviderId.GEMINI)).isFalse();
	}

	@Test
	void withoutRetryAfterTheDefaultCoolDownApplies() {
		cooldowns.start(ProviderId.GEMINI, null);

		clock.advance(Duration.ofSeconds(29));
		assertThat(cooldowns.isCoolingDown(ProviderId.GEMINI)).isTrue();
		clock.advance(Duration.ofSeconds(2));
		assertThat(cooldowns.isCoolingDown(ProviderId.GEMINI)).isFalse();
	}

	@Test
	void retryAfterIsHonouredButCappedAtTheMaximum() {
		cooldowns.start(ProviderId.GROQ, Duration.ofSeconds(5));
		cooldowns.start(ProviderId.GEMINI, Duration.ofHours(1));

		clock.advance(Duration.ofSeconds(6));
		assertThat(cooldowns.isCoolingDown(ProviderId.GROQ)).isFalse();
		clock.advance(Duration.ofSeconds(50));
		assertThat(cooldowns.isCoolingDown(ProviderId.GEMINI)).isTrue(); // 56 s into a 60 s cap
		clock.advance(Duration.ofSeconds(5));
		assertThat(cooldowns.isCoolingDown(ProviderId.GEMINI)).isFalse();
	}

	@Test
	void theProvidersCoolDownIndependently() {
		cooldowns.start(ProviderId.GEMINI, null);

		assertThat(cooldowns.isCoolingDown(ProviderId.GEMINI)).isTrue();
		assertThat(cooldowns.isCoolingDown(ProviderId.GROQ)).isFalse();
	}
}
