package com.abhiraj.archyra.security;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Token buckets in memory, one per (rule, key), kept in bounded Caffeine caches. A bucket that has been idle for a
 * whole refill period is dropped, which is the same as a full one, so eviction never lets anyone through early.
 *
 * <p>TODO: this is per instance. With more than one server instance the limits must move to a shared store
 * (Redis, which is already on the roadmap); Bucket4j supports that without changing the callers.
 */
@Component
public class RateLimiter {

	/** What is limited. The key is the client IP, except for {@link #CREATE_ANALYSIS}, which is per user id. */
	public enum Rule {
		LOGIN, SENSITIVE, CREATE_ANALYSIS
	}

	/** @param retryAfterSeconds how long until a request would be let through; 0 when allowed */
	public record Decision(boolean allowed, long retryAfterSeconds) {
	}

	private static final long MAX_KEYS = 100_000;

	private final Map<Rule, Limit> limits = new EnumMap<>(Rule.class);

	private record Limit(long capacity, Duration period, Cache<String, Bucket> buckets) {
	}

	public RateLimiter(SecurityProperties properties) {
		var config = properties.rateLimit();
		add(Rule.LOGIN, config.loginPerIpPerMinute(), Duration.ofMinutes(1));
		add(Rule.SENSITIVE, config.sensitivePerIpPerHour(), Duration.ofHours(1));
		add(Rule.CREATE_ANALYSIS, config.createAnalysisPerUserPerHour(), Duration.ofHours(1));
	}

	private void add(Rule rule, long capacity, Duration period) {
		limits.put(rule, new Limit(capacity, period,
				Caffeine.newBuilder().maximumSize(MAX_KEYS).expireAfterAccess(period).build()));
	}

	/** Takes one token for the key, if there is one. */
	public Decision tryConsume(Rule rule, String key) {
		Limit limit = limits.get(rule);
		Bucket bucket = limit.buckets().get(key, k -> Bucket.builder()
				.addLimit(Bandwidth.builder().capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()).build())
				.build());
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			return new Decision(true, 0);
		}
		long seconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
		return new Decision(false, seconds);
	}
}
