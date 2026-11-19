package com.abhiraj.archyra.ai.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Providers that answered 429 are left alone for a while: the {@code Retry-After} they sent, capped at
 * {@code archyra.ai.provider.cooldown-max}, or {@code cooldown-default} when they did not say. A small Caffeine map; the
 * pre-flight check of a question uses it, so nobody is charged and refunded in a loop while both providers are throttled.
 */
@Component
public class ProviderCooldowns {

	private final AiProperties properties;
	private final Clock clock;
	private final Cache<ProviderId, Instant> until;

	ProviderCooldowns(AiProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.until = Caffeine.newBuilder().expireAfterWrite(properties.provider().cooldownMax().plusSeconds(1)).build();
	}

	/** Starts (or restarts) the cool-down of a provider that answered 429. */
	void start(ProviderId provider, Duration retryAfter) {
		Duration max = properties.provider().cooldownMax();
		Duration duration = retryAfter == null ? properties.provider().cooldownDefault() : retryAfter;
		if (duration.compareTo(max) > 0) {
			duration = max;
		}
		until.put(provider, clock.instant().plus(duration));
	}

	public boolean isCoolingDown(ProviderId provider) {
		Instant end = until.getIfPresent(provider);
		return end != null && clock.instant().isBefore(end);
	}
}
