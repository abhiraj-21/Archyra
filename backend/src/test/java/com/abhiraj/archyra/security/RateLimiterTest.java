package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.security.RateLimiter.Rule;

class RateLimiterTest {

	// the test properties allow 10 logins per minute, 5 sensitive requests and 10 analyses per hour
	private final RateLimiter limiter = new RateLimiter(TestSecurityProperties.create());

	@Test
	void theConfiguredNumberOfRequestsIsAllowedThenRefused() {
		for (int i = 0; i < 10; i++) {
			assertThat(limiter.tryConsume(Rule.LOGIN, "203.0.113.1").allowed()).as("request %d", i + 1).isTrue();
		}

		RateLimiter.Decision refused = limiter.tryConsume(Rule.LOGIN, "203.0.113.1");

		assertThat(refused.allowed()).isFalse();
		assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
	}

	@Test
	void eachKeyHasItsOwnBucket() {
		for (int i = 0; i < 10; i++) {
			limiter.tryConsume(Rule.LOGIN, "203.0.113.1");
		}

		assertThat(limiter.tryConsume(Rule.LOGIN, "203.0.113.1").allowed()).isFalse();
		assertThat(limiter.tryConsume(Rule.LOGIN, "203.0.113.2").allowed()).isTrue();
	}

	@Test
	void eachRuleHasItsOwnBucketAndLimit() {
		for (int i = 0; i < 5; i++) {
			assertThat(limiter.tryConsume(Rule.SENSITIVE, "k").allowed()).isTrue();
		}

		RateLimiter.Decision refused = limiter.tryConsume(Rule.SENSITIVE, "k");

		assertThat(refused.allowed()).isFalse();
		assertThat(refused.retryAfterSeconds()).isBetween(1L, 3600L);
		assertThat(limiter.tryConsume(Rule.LOGIN, "k").allowed()).isTrue();
		assertThat(limiter.tryConsume(Rule.CREATE_ANALYSIS, "k").allowed()).isTrue();
	}

	@Test
	void anAllowedDecisionHasNoWait() {
		assertThat(limiter.tryConsume(Rule.CREATE_ANALYSIS, "user-1")).isEqualTo(new RateLimiter.Decision(true, 0));
	}
}
