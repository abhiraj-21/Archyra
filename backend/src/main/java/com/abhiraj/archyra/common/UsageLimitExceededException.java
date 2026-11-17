package com.abhiraj.archyra.common;

/** A free user has used up a lifetime allowance. Mapped to {@code 403 USAGE_LIMIT_EXCEEDED}. */
public class UsageLimitExceededException extends RuntimeException {

	public UsageLimitExceededException(String message) {
		super(message);
	}
}
