package com.abhiraj.archyra.common;

/** A premium feature requested on the free plan. Mapped to {@code 403 PREMIUM_FEATURE}. */
public class PremiumRequiredException extends RuntimeException {

	public PremiumRequiredException(String message) {
		super(message);
	}
}
