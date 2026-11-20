package com.abhiraj.archyra.billing;

import java.util.Locale;
import java.util.Optional;

/** The states of a Razorpay Subscription, as Razorpay names them (lower case on the wire). */
public enum SubscriptionStatus {
	CREATED,
	AUTHENTICATED,
	ACTIVE,
	PENDING,
	HALTED,
	CANCELLED,
	COMPLETED;

	/** Live at Razorpay: it may still charge. A halted one does not charge until the customer acts, so it is not live. */
	public boolean live() {
		return this == AUTHENTICATED || this == ACTIVE || this == PENDING;
	}

	/** Nothing moves a subscription out of these. */
	public boolean terminal() {
		return this == CANCELLED || this == COMPLETED;
	}

	/** Razorpay's value ({@code active}, {@code halted}, ...), or empty for one this code does not know (expired, paused, ...). */
	public static Optional<SubscriptionStatus> fromRazorpay(String value) {
		if (value == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/**
	 * Where a subscription in this state goes when an event says {@code next}. Webhooks can arrive out of order, so a
	 * terminal state is final and a late {@code authenticated} never pulls an active subscription back.
	 */
	public SubscriptionStatus then(SubscriptionStatus next) {
		if (terminal()) {
			return this;
		}
		if (next == AUTHENTICATED && this != CREATED) {
			return this;
		}
		if (next == CREATED) {
			return this;
		}
		return next;
	}
}
