package com.abhiraj.archyra.ai.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand. */
public final class MovableClock extends Clock {

	private volatile Instant now = Instant.parse("2026-11-19T10:00:00Z");

	public void advance(Duration duration) {
		now = now.plus(duration);
	}

	/** Jumps to an absolute instant (earlier or later). */
	public void setTo(Instant instant) {
		now = instant;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}
}
