package com.abhiraj.archyra.billing;

import java.time.Instant;
import java.time.ZoneId;

/**
 * What one payment buys. A period is a calendar month or year in the billing zone ({@code ZonedDateTime}), never a fixed
 * number of hours, so 31 January plus one month is the last day of February at the same local time.
 */
public enum BillingPeriod {
	MONTHLY,
	YEARLY;

	/** {@code from} plus one period, in calendar terms of {@code zone}. */
	public Instant addTo(Instant from, ZoneId zone) {
		var local = from.atZone(zone);
		return switch (this) {
			case MONTHLY -> local.plusMonths(1).toInstant();
			case YEARLY -> local.plusYears(1).toInstant();
		};
	}

	/** The Razorpay subscription cycle count: ten years either way. */
	public int subscriptionCycles() {
		return switch (this) {
			case MONTHLY -> 120;
			case YEARLY -> 10;
		};
	}
}
