package com.abhiraj.archyra.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** A period is a calendar month or year in the billing zone (Asia/Kolkata, UTC+05:30), never a number of hours. */
class BillingPeriodTest {

	private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@Test
	void aMonthIsTheSameLocalTimeNextMonth() {
		// 20 November 2026, 15:30 IST
		assertThat(BillingPeriod.MONTHLY.addTo(Instant.parse("2026-11-20T10:00:00Z"), KOLKATA)).isEqualTo(Instant.parse("2026-12-20T10:00:00Z"));
	}

	@Test
	void theThirtyFirstOfJanuaryEndsOnTheLastDayOfFebruary() {
		// 31 January 2027, 10:00 IST -> 28 February 2027, 10:00 IST
		assertThat(BillingPeriod.MONTHLY.addTo(Instant.parse("2027-01-31T04:30:00Z"), KOLKATA)).isEqualTo(Instant.parse("2027-02-28T04:30:00Z"));
		// in a leap year, the 29th
		assertThat(BillingPeriod.MONTHLY.addTo(Instant.parse("2028-01-31T04:30:00Z"), KOLKATA)).isEqualTo(Instant.parse("2028-02-29T04:30:00Z"));
	}

	@Test
	void theMonthIsDecidedByTheLocalDateNotTheUtcDate() {
		// 31 January 2027, 23:00 UTC is already 1 February 2027, 04:30 IST: one month later is 1 March, 04:30 IST
		assertThat(BillingPeriod.MONTHLY.addTo(Instant.parse("2027-01-31T23:00:00Z"), KOLKATA)).isEqualTo(Instant.parse("2027-02-28T23:00:00Z"));
	}

	@Test
	void aYearFromTheTwentyNinthOfFebruaryIsTheTwentyEighth() {
		assertThat(BillingPeriod.YEARLY.addTo(Instant.parse("2028-02-29T06:30:00Z"), KOLKATA)).isEqualTo(Instant.parse("2029-02-28T06:30:00Z"));
		assertThat(BillingPeriod.YEARLY.addTo(Instant.parse("2026-11-20T10:00:00Z"), KOLKATA)).isEqualTo(Instant.parse("2027-11-20T10:00:00Z"));
	}

	@Test
	void aSubscriptionRunsTenYearsEitherWay() {
		assertThat(BillingPeriod.MONTHLY.subscriptionCycles()).isEqualTo(120);
		assertThat(BillingPeriod.YEARLY.subscriptionCycles()).isEqualTo(10);
	}
}
