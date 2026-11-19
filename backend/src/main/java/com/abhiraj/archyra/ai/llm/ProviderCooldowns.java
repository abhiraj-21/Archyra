package com.abhiraj.archyra.ai.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;

/**
 * Providers that failed in a way that makes asking again pointless are left alone for a while. A small in-memory map; the
 * pre-flight check of a question uses it, so nobody is charged and refunded in a loop while every provider is cooling.
 *
 * <ul>
 *   <li><b>429</b>: the first one waits for the provider's {@code Retry-After}, or {@code cooldown-default} when it did not
 *       say. Every further 429 in a row (no success in between) waits at least twice as long as the one before (default,
 *       2 x default, 4 x default ...) and never less than {@code Retry-After}, up to {@code cooldown-max}. So a provider
 *       whose daily quota is spent is probed less and less often instead of on every question.</li>
 *   <li><b>Bad key, unknown model</b> (401, 402, 403, 404): {@code cooldown-misconfigured}, a long wait; the gateway logs
 *       it loudly.</li>
 *   <li>A success clears the escalation.</li>
 * </ul>
 */
@Component
public class ProviderCooldowns {

	private record State(Instant until, int strikes, Instant lastFailure) {
	}

	private final AiProperties.Providers properties;
	private final Clock clock;
	private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

	ProviderCooldowns(AiProperties properties, Clock clock) {
		this.properties = properties.providers();
		this.clock = clock;
	}

	/** Starts (or lengthens) the cool-down of a provider that answered 429; returns how long it is. */
	Duration rateLimited(String provider, Duration retryAfter) {
		Instant now = clock.instant();
		Duration max = properties.cooldownMax();
		Duration[] chosen = new Duration[1];
		states.compute(provider, (id, old) -> {
			int strikes = old == null || now.isAfter(old.lastFailure().plus(max.multipliedBy(2))) ? 0 : old.strikes();
			Duration duration = strikes == 0
					? (retryAfter == null ? properties.cooldownDefault() : retryAfter)
					: longer(retryAfter, backoff(strikes));
			if (duration.compareTo(max) > 0) {
				duration = max;
			}
			chosen[0] = duration;
			return new State(now.plus(duration), strikes + 1, now);
		});
		return chosen[0];
	}

	/** A long cool-down for a provider whose key or model is wrong. */
	Duration misconfigured(String provider) {
		Instant now = clock.instant();
		Duration duration = properties.cooldownMisconfigured();
		states.put(provider, new State(now.plus(duration), 0, now));
		return duration;
	}

	/** An answer arrived: the escalation starts over. */
	void succeeded(String provider) {
		states.remove(provider);
	}

	public boolean isCoolingDown(String provider) {
		return coolingUntil(provider) != null;
	}

	/** When the cool-down ends, or null when the provider is not cooling down. */
	public Instant coolingUntil(String provider) {
		State state = states.get(provider);
		return state != null && clock.instant().isBefore(state.until()) ? state.until() : null;
	}

	private Duration backoff(int strikes) {
		Duration base = properties.cooldownDefault();
		Duration max = properties.cooldownMax();
		Duration value = base;
		for (int i = 0; i < strikes && value.compareTo(max) < 0; i++) {
			value = value.multipliedBy(2);
		}
		return value.compareTo(max) > 0 ? max : value;
	}

	private static Duration longer(Duration retryAfter, Duration backoff) {
		return retryAfter != null && retryAfter.compareTo(backoff) > 0 ? retryAfter : backoff;
	}
}
