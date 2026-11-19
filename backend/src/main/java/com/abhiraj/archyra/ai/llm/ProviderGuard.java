package com.abhiraj.archyra.ai.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

/**
 * The local limits of one provider, so a free quota is not burnt on calls that would be refused anyway: a Bucket4j token
 * bucket for requests per minute, a semaphore for calls in flight, and counters of the requests of the current UTC day and
 * UTC month. A limit of 0 means "none". The counters live in memory: a restart resets them, which is accepted because the
 * provider's own 429 then starts a cool-down.
 *
 * <p>{@link #tryAcquire} never waits: a refusal makes the gateway go on to the next provider, and a refused call neither
 * takes a token nor counts as a request.
 */
final class ProviderGuard {

	/** Why a call was refused locally. */
	enum Refusal {
		PER_MINUTE, CONCURRENCY, PER_DAY, PER_MONTH
	}

	/** Held while a call is in flight; releasing it twice is harmless. */
	static final class Permit {

		private final Semaphore semaphore;
		private final AtomicBoolean released = new AtomicBoolean();

		private Permit(Semaphore semaphore) {
			this.semaphore = semaphore;
		}

		void release() {
			if (semaphore != null && released.compareAndSet(false, true)) {
				semaphore.release();
			}
		}
	}

	/** Either a permit or the reason for the refusal. */
	record Acquired(Permit permit, Refusal refusal) {
		boolean granted() {
			return permit != null;
		}
	}

	private final int perDay;
	private final int perMonth;
	private final Clock clock;
	private final Bucket perMinute; // null: no limit
	private final Semaphore concurrency; // null: no limit

	private LocalDate day;
	private YearMonth month;
	private long today;
	private long thisMonth;

	ProviderGuard(ProviderSettings settings, Clock clock) {
		this.clock = clock;
		this.perDay = settings.maxRequestsPerDay();
		this.perMonth = settings.maxRequestsPerMonth();
		int rpm = settings.requestsPerMinute();
		this.perMinute = rpm <= 0 ? null : Bucket.builder()
				.addLimit(Bandwidth.builder().capacity(rpm).refillGreedy(rpm, Duration.ofMinutes(1)).build()).build();
		this.concurrency = settings.maxConcurrent() <= 0 ? null : new Semaphore(settings.maxConcurrent());
	}

	synchronized Acquired tryAcquire() {
		rollOver();
		if (perDay > 0 && today >= perDay) {
			return new Acquired(null, Refusal.PER_DAY);
		}
		if (perMonth > 0 && thisMonth >= perMonth) {
			return new Acquired(null, Refusal.PER_MONTH);
		}
		if (concurrency != null && !concurrency.tryAcquire()) {
			return new Acquired(null, Refusal.CONCURRENCY);
		}
		if (perMinute != null && !perMinute.tryConsume(1)) {
			if (concurrency != null) {
				concurrency.release();
			}
			return new Acquired(null, Refusal.PER_MINUTE);
		}
		today++;
		thisMonth++;
		return new Acquired(new Permit(concurrency), null);
	}

	/** Whether a call would be allowed right now, without taking anything: the pre-flight check and the admin view. */
	synchronized Refusal wouldRefuse() {
		rollOver();
		if (perDay > 0 && today >= perDay) {
			return Refusal.PER_DAY;
		}
		if (perMonth > 0 && thisMonth >= perMonth) {
			return Refusal.PER_MONTH;
		}
		if (concurrency != null && concurrency.availablePermits() == 0) {
			return Refusal.CONCURRENCY;
		}
		if (perMinute != null && perMinute.getAvailableTokens() < 1) {
			return Refusal.PER_MINUTE;
		}
		return null;
	}

	synchronized long requestsToday() {
		rollOver();
		return today;
	}

	synchronized long requestsThisMonth() {
		rollOver();
		return thisMonth;
	}

	private void rollOver() {
		LocalDate nowDay = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		if (!nowDay.equals(day)) {
			day = nowDay;
			today = 0;
		}
		YearMonth nowMonth = YearMonth.from(nowDay);
		if (!nowMonth.equals(month)) {
			month = nowMonth;
			thisMonth = 0;
		}
	}
}
