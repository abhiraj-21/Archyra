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
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AnalysisRecovery {

	private final AnalysisRepository analysisRepository;

	@EventListener(ApplicationReadyEvent.class)
	void failInterruptedAnalyses() {
		List<Analysis> interrupted = analysisRepository.findByStatusNotIn(
				List.of(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED));
		for (Analysis analysis : interrupted) {
			analysis.markFailed("The analysis was interrupted by a server restart");
		}
		analysisRepository.saveAll(interrupted);
		if (!interrupted.isEmpty()) {
			log.warn("Marked {} interrupted analyses as FAILED", interrupted.size());
		}
	}
}
