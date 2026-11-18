package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * At most one "you already have an account" notice per address per hour, so the register form cannot be used to fill
 * a victim's inbox. In addition to the per-IP limits. In memory and bounded; keyed by a hash of the lower-cased
 * address, so the cache never holds an address. Like {@code RateLimiter} it is per instance.
 */
@Component
class RegisterNoticeThrottle {

	static final Duration INTERVAL = Duration.ofHours(1);
	private static final long MAX_ADDRESSES = 50_000;

	private final Cache<String, Boolean> sent;

	RegisterNoticeThrottle(Clock clock) {
		this.sent = Caffeine.newBuilder().maximumSize(MAX_ADDRESSES).expireAfterWrite(INTERVAL)
				.ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).build();
	}

	/** True, and starts the hour, if no notice went to this address in the last hour. */
	boolean tryAcquire(String normalizedEmail) {
		return sent.asMap().putIfAbsent(TokenHasher.hash(normalizedEmail), Boolean.TRUE) == null;
	}
}
