package com.abhiraj.archyra.usage;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

import org.springframework.stereotype.Component;

/**
 * The weekly window of the allowances that reset: Monday 00:00 (inclusive) to the next Monday 00:00 (exclusive) in
 * {@code archyra.usage.reset-zone}. Everything is computed from local dates with {@link java.time.ZonedDateTime}
 * rules, never by adding {@code 7 * 24} hours, so a week that contains a daylight-saving change is 167 or 169 hours
 * long and still starts at local midnight. The clock is injected, so tests move time by hand.
 */
@Component
public class UsageWindows {

	private final ZoneId zone;
	private final Clock clock;

	UsageWindows(UsageProperties properties, Clock clock) {
		this.zone = properties.zone();
		this.clock = clock;
	}

	/** The start of the window that contains now. */
	public Instant currentStart() {
		return currentStart(clock.instant());
	}

	/** The moment the allowance starts again: the start of the next window. */
	public Instant nextReset() {
		return nextReset(clock.instant());
	}

	Instant currentStart(Instant at) {
		return mondayOf(at).atStartOfDay(zone).toInstant();
	}

	Instant nextReset(Instant at) {
		return mondayOf(at).plusWeeks(1).atStartOfDay(zone).toInstant();
	}

	private LocalDate mondayOf(Instant at) {
		return at.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}
}
