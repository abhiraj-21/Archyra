package com.abhiraj.archyra.analysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Records that the owner used a repository ({@code analyses.last_activity_at}), which decides which repositories a user
 * keeps when Premium lapses ({@link AnalysisRetention}). Called when an analysis is created and when it completes, when the
 * owner opens it (from {@link AnalysisService#requireCompleted}, after the ownership check, so a refused request never
 * touches) and for every Ask AI question that is answered. One guarded UPDATE writes at most once per
 * {@code archyra.billing.touch-interval}. Its own short transaction, and it never throws: a read never fails because the
 * activity could not be recorded. PostgreSQL only.
 */
@Slf4j
@Component
public class AnalysisActivity {

	private final AnalysisRepository analyses;
	private final Clock clock;
	private final Duration interval;

	AnalysisActivity(AnalysisRepository analyses, Clock clock, @Value("${archyra.billing.touch-interval:10m}") Duration interval) {
		this.analyses = analyses;
		this.clock = clock;
		this.interval = interval;
	}

	/** @return true when this call wrote the time (false: throttled, unknown id, or the write failed) */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean touch(UUID analysisId) {
		if (analysisId == null) {
			return false;
		}
		try {
			Instant now = clock.instant();
			return analyses.touch(analysisId, now, now.minus(interval)) == 1;
		}
		catch (RuntimeException ex) {
			log.debug("Could not record the activity of analysis {} ({})", analysisId, ex.getClass().getSimpleName());
			return false;
		}
	}
}
