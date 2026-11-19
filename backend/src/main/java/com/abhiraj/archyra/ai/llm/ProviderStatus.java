package com.abhiraj.archyra.ai.llm;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One provider as the admin endpoint shows it. Nothing here is secret: no key, no key fragment, no URL.
 *
 * @param configured   has a usable base URL, a model and (where required) a key
 * @param enabled      not switched off by configuration (opt-in providers are off unless enabled explicitly)
 * @param state        {@code DISABLED} (not enabled or not configured: not in the chain), {@code COOLING_DOWN},
 *                     {@code RATE_LIMITED_LOCALLY} (one of its own limits is used up) or {@code AVAILABLE}
 * @param coolingUntil only while cooling down
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderStatus(String id, String displayName, boolean configured, boolean enabled, State state, Instant coolingUntil,
		long requestsToday, long requestsThisMonth) {

	public enum State {
		AVAILABLE, COOLING_DOWN, RATE_LIMITED_LOCALLY, DISABLED
	}
}
