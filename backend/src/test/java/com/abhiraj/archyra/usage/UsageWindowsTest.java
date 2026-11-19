package com.abhiraj.archyra.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ai.llm.MovableClock;

/** The week arithmetic: Monday 00:00 in the configured zone, inclusive, to the next Monday 00:00, exclusive. */
class UsageWindowsTest {

	private static UsageWindows windows(String zone, Instant now) {
		MovableClock clock = new MovableClock();
		clock.setTo(now);
		return new UsageWindows(new UsageProperties(new UsageProperties.FreeTier(1, 1), "archyra-test-only-usage-ledger-secret-0123456789", zone), clock);
	}

	private static Instant local(String zone, int year, int month, int day, int hour, int minute, int second) {
		return ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneId.of(zone)).toInstant();
	}

	// ---- Asia/Kolkata (the default, +05:30, no DST) ------------------------------------------------

	@Test
	void aWednesdayBelongsToTheWeekThatStartedOnMonday() {
		UsageWindows windows = windows("Asia/Kolkata", Instant.parse("2026-11-18T10:00:00Z")); // Wednesday 15:30 IST

		assertThat(windows.currentStart()).isEqualTo(Instant.parse("2026-11-15T18:30:00Z")); // Monday 2026-11-16 00:00 IST
		assertThat(windows.nextReset()).isEqualTo(Instant.parse("2026-11-22T18:30:00Z")); // Monday 2026-11-23 00:00 IST
	}

	@Test
	void theLastSecondBeforeMondayIsStillTheOldWeek() {
		UsageWindows windows = windows("Asia/Kolkata", Instant.parse("2026-11-22T18:29:59Z")); // Sunday 23:59:59 IST

		assertThat(windows.currentStart()).isEqualTo(Instant.parse("2026-11-15T18:30:00Z"));
		assertThat(windows.nextReset()).isEqualTo(Instant.parse("2026-11-22T18:30:00Z"));
	}

	@Test
	void exactlyMondayMidnightStartsTheNewWeek() {
		UsageWindows windows = windows("Asia/Kolkata", Instant.parse("2026-11-22T18:30:00Z")); // Monday 00:00:00 IST

		assertThat(windows.currentStart()).isEqualTo(Instant.parse("2026-11-22T18:30:00Z"));
		assertThat(windows.nextReset()).isEqualTo(Instant.parse("2026-11-29T18:30:00Z"));
	}

	@Test
	void theSameInstantIsAnotherWeekdayInUtcAndStillTheOldKolkataWeek() {
		// Sunday 2026-11-22 19:00 in UTC is already Monday 00:30 in Kolkata: a UTC calculation would say "Sunday"
		UsageWindows windows = windows("Asia/Kolkata", Instant.parse("2026-11-22T19:00:00Z"));

		assertThat(windows.currentStart()).isEqualTo(Instant.parse("2026-11-22T18:30:00Z"));
	}

	@Test
	void aSundayInUtcThatIsMondayInAucklandIsTheNewAucklandWeek() {
		UsageWindows windows = windows("Pacific/Auckland", Instant.parse("2026-11-15T12:00:00Z")); // Monday 01:00 NZDT

		assertThat(windows.currentStart()).isEqualTo(local("Pacific/Auckland", 2026, 11, 16, 0, 0, 0));
		assertThat(windows.nextReset()).isEqualTo(local("Pacific/Auckland", 2026, 11, 23, 0, 0, 0));
	}

	// ---- daylight saving -----------------------------------------------------------------------------

	@Test
	void aWeekWithTheSpringForwardIs167HoursAndStillStartsAtLocalMidnight() {
		UsageWindows windows = windows("America/New_York", Instant.parse("2026-03-05T12:00:00Z"));

		assertThat(windows.currentStart()).isEqualTo(Instant.parse("2026-03-02T05:00:00Z")); // 00:00 EST
		assertThat(windows.nextReset()).isEqualTo(Instant.parse("2026-03-09T04:00:00Z")); // 00:00 EDT
		assertThat(Duration.between(windows.currentStart(), windows.nextReset())).isEqualTo(Duration.ofHours(167));
	}

	@Test
	void aWeekWithTheFallBackIs169Hours() {
		UsageWindows windows = windows("America/New_York", Instant.parse("2026-10-28T12:00:00Z"));

		assertThat(windows.currentStart()).isEqualTo(Instant.parse("2026-10-26T04:00:00Z")); // 00:00 EDT
		assertThat(windows.nextReset()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z")); // 00:00 EST
		assertThat(Duration.between(windows.currentStart(), windows.nextReset())).isEqualTo(Duration.ofHours(169));
	}

	@Test
	void theNightOfTheSpringForwardStaysInTheSameWeek() {
		// Sunday 2026-03-08: 01:59:59 EST, then the clocks jump to 03:00 EDT. Both sides are still the old week.
		assertThat(windows("America/New_York", Instant.parse("2026-03-08T06:59:59Z")).currentStart()).isEqualTo(Instant.parse("2026-03-02T05:00:00Z"));
		assertThat(windows("America/New_York", Instant.parse("2026-03-08T07:00:00Z")).currentStart()).isEqualTo(Instant.parse("2026-03-02T05:00:00Z"));
	}

	@Test
	void theWeekAfterTheSpringForwardStartsOneHourEarlierInUtc() {
		UsageWindows windows = windows("America/New_York", Instant.parse("2026-03-09T04:00:00Z")); // Monday 00:00 EDT sharp

		assertThat(windows.currentStart()).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
		assertThat(windows("America/New_York", Instant.parse("2026-03-09T03:59:59Z")).currentStart()).isEqualTo(Instant.parse("2026-03-02T05:00:00Z"));
	}

	@Test
	void londonInSummerAndWinter() {
		assertThat(windows("Europe/London", Instant.parse("2026-07-15T12:00:00Z")).currentStart()).isEqualTo(Instant.parse("2026-07-12T23:00:00Z")); // 00:00 BST
		assertThat(windows("Europe/London", Instant.parse("2026-12-09T12:00:00Z")).currentStart()).isEqualTo(Instant.parse("2026-12-07T00:00:00Z")); // 00:00 GMT
	}

	// ---- year boundary -------------------------------------------------------------------------------

	@Test
	void aWeekCanSpanTheNewYear() {
		UsageWindows newYearsEve = windows("UTC", Instant.parse("2026-12-31T23:59:59Z"));
		UsageWindows newYearsDay = windows("UTC", Instant.parse("2027-01-01T00:00:00Z"));

		assertThat(newYearsEve.currentStart()).isEqualTo(Instant.parse("2026-12-28T00:00:00Z"));
		assertThat(newYearsEve.nextReset()).isEqualTo(Instant.parse("2027-01-04T00:00:00Z"));
		assertThat(newYearsDay.currentStart()).isEqualTo(Instant.parse("2026-12-28T00:00:00Z")); // same week
	}

	@Test
	void theNewYearInKolkataComesBeforeUtcAndStillIsTheSameWeek() {
		UsageWindows windows = windows("Asia/Kolkata", Instant.parse("2026-12-31T20:00:00Z")); // 2027-01-01 01:30 IST, Friday

		assertThat(windows.currentStart()).isEqualTo(local("Asia/Kolkata", 2026, 12, 28, 0, 0, 0));
		assertThat(windows.nextReset()).isEqualTo(local("Asia/Kolkata", 2027, 1, 4, 0, 0, 0));
	}

	@Test
	void aNewYearsDayThatIsAMondayStartsTheWeekAndTheYear() {
		UsageWindows windows = windows("UTC", Instant.parse("2029-01-01T00:00:00Z")); // a Monday

		assertThat(windows.currentStart()).isEqualTo(Instant.parse("2029-01-01T00:00:00Z"));
		assertThat(windows.nextReset()).isEqualTo(Instant.parse("2029-01-08T00:00:00Z"));
		assertThat(windows("UTC", Instant.parse("2028-12-31T23:59:59Z")).nextReset()).isEqualTo(Instant.parse("2029-01-01T00:00:00Z"));
	}

	@Test
	void theWindowAlwaysContainsNowAndIsContiguousWithTheNext() {
		Instant moment = Instant.parse("2026-03-04T00:00:00Z");
		for (String zone : new String[] { "Asia/Kolkata", "America/New_York", "Pacific/Auckland", "UTC", "Europe/London", "America/Sao_Paulo" }) {
			for (int hours = 0; hours < 24 * 400; hours += 7) {
				Instant at = moment.plus(Duration.ofHours(hours));
				UsageWindows windows = windows(zone, at);

				assertThat(windows.currentStart()).isBeforeOrEqualTo(at);
				assertThat(windows.nextReset()).isAfter(at);
				// the next window starts exactly where this one ends
				assertThat(windows(zone, windows.nextReset()).currentStart()).isEqualTo(windows.nextReset());
				assertThat(windows(zone, windows.nextReset().minusSeconds(1)).currentStart()).isEqualTo(windows.currentStart());
				// and it is local midnight on a Monday
				ZonedDateTime start = windows.currentStart().atZone(ZoneId.of(zone));
				assertThat(start.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
				assertThat(start.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
			}
		}
	}
}
