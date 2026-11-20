package com.abhiraj.archyra.analysis;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Analyses run in memory, so one that was queued or running when the server stopped will never finish.
 * On startup they are marked FAILED instead of staying in an intermediate status forever. This assumes a
 * single server instance, which holds until background processing moves to a queue.
 *
 * <p>Their quota units go back too, and so do those of analyses that FAILED but whose refund did not go through
 * (the refund is idempotent, so running it again is always safe).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AnalysisRecovery {

	private final AnalysisRepository analysisRepository;
	private final AnalysisRegistrar registrar;

	@EventListener(ApplicationReadyEvent.class)
	void failInterruptedAnalyses() {
		// A restored NOT_ANALYZED entry is idle, not interrupted: no run ever owned it.
		List<Analysis> interrupted = analysisRepository.findByStatusNotIn(
				List.of(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED, AnalysisStatus.NOT_ANALYZED));
		for (Analysis analysis : interrupted) {
			analysis.markFailed("The analysis was interrupted by a server restart");
		}
		analysisRepository.saveAll(interrupted);
		if (!interrupted.isEmpty()) {
			log.warn("Marked {} interrupted analyses as FAILED", interrupted.size());
		}

		// After the save above this includes the ones just interrupted.
		for (Analysis analysis : analysisRepository.findByStatusAndQuotaChargedTrueAndQuotaRefundedFalse(AnalysisStatus.FAILED)) {
			try {
				registrar.refundQuota(analysis.getId());
			}
			catch (RuntimeException ex) {
				log.error("Could not return the quota unit of failed analysis {}", analysis.getId(), ex);
			}
		}
	}
}
