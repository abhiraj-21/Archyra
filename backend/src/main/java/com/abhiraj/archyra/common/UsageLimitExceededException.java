package com.abhiraj.archyra.common;

import java.time.Instant;

/**
 * A free user has used up an allowance. Mapped to {@code 403 USAGE_LIMIT_EXCEEDED}. A weekly allowance (Ask AI) carries
 * the moment it starts again, which goes into the error body as {@code resetsAt}; a lifetime one has none.
 */
public class UsageLimitExceededException extends RuntimeException {

	private final Instant resetsAt;

	public UsageLimitExceededException(String message) {
		this(message, null);
	}

	public UsageLimitExceededException(String message, Instant resetsAt) {
		super(message);
		this.resetsAt = resetsAt;
	}

	/** When the allowance starts again, or null for a lifetime allowance. */
	public Instant resetsAt() {
		return resetsAt;
	}
}
