package com.abhiraj.archyra.common;

/** Too many requests. Mapped to {@code 429 RATE_LIMITED} with a {@code Retry-After} header. */
public class RateLimitedException extends RuntimeException {

	private final long retryAfterSeconds;

	public RateLimitedException(long retryAfterSeconds) {
		super("Too many requests. Try again later.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
