package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RegisterNoticeThrottleTest {

	/** A clock the test moves by hand. */
	private static final class Manual extends Clock {
		private Instant now = Instant.parse("2026-11-19T10:00:00Z");

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	void anAddressGetsOneNoticePerHour() {
		Manual clock = new Manual();
		RegisterNoticeThrottle throttle = new RegisterNoticeThrottle(clock);

		assertThat(throttle.tryAcquire("a@example.com")).isTrue();
		assertThat(throttle.tryAcquire("a@example.com")).isFalse();
		clock.advance(Duration.ofMinutes(59));
		assertThat(throttle.tryAcquire("a@example.com")).isFalse();
		clock.advance(Duration.ofMinutes(2));
		assertThat(throttle.tryAcquire("a@example.com")).isTrue();
		assertThat(throttle.tryAcquire("a@example.com")).isFalse();
	}

	@Test
	void addressesAreThrottledIndependently() {
		RegisterNoticeThrottle throttle = new RegisterNoticeThrottle(new Manual());

		assertThat(throttle.tryAcquire("a@example.com")).isTrue();
		assertThat(throttle.tryAcquire("b@example.com")).isTrue();
		assertThat(throttle.tryAcquire("a@example.com")).isFalse();
		assertThat(throttle.tryAcquire("b@example.com")).isFalse();
	}
}
