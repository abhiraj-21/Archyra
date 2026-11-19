package com.abhiraj.archyra.ai.llm;

import java.time.Duration;

/**
 * A provider call that did not give an answer. Every kind makes the gateway try the next provider. The message is fixed
 * text: it never carries a provider's response body, the prompt or a key.
 */
public class ProviderException extends RuntimeException {

	public enum Kind {
		/** 429. The provider is put in cool-down. */
		RATE_LIMITED,
		/** 5xx or no connection. */
		UNAVAILABLE,
		TIMEOUT,
		/** 401 or 403: the key is wrong or revoked. Logged loudly. */
		BAD_KEY,
		/** No text came back (blocked, or the whole output went into thinking). */
		EMPTY,
		/** Anything else, for example a 400. */
		OTHER
	}

	private final ProviderId provider;
	private final Kind kind;
	private final Duration retryAfter;

	public ProviderException(ProviderId provider, Kind kind, Duration retryAfter, Throwable cause) {
		super(provider + " " + kind, cause);
		this.provider = provider;
		this.kind = kind;
		this.retryAfter = retryAfter;
	}

	public ProviderId provider() {
		return provider;
	}

	public Kind kind() {
		return kind;
	}

	/** How long the provider asked to be left alone (a 429's {@code Retry-After}); null when it did not say. */
	public Duration retryAfter() {
		return retryAfter;
	}
}
