package com.abhiraj.archyra.ai.index;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.analysis.AnalysisService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps indexes moving: at startup it drops the data of analyses that did not COMPLETE and resumes INDEXING rows
 * (a restart interrupted their job), and a scheduled sweep resumes INDEXING rows that have no job, for example because
 * the embedding queue was full. Resuming continues where the job stopped: only chunks without a vector are embedded.
 * Assumes a single instance, as elsewhere in the backend.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class IndexRecovery {

	private final AiIndexRepository indexes;
	private final ChunkStore chunks;
	private final EmbeddingJobs jobs;
	private final AnalysisService analyses;

	@EventListener(ApplicationReadyEvent.class)
	void onStartup() {
		try {
			purgeUnfinished();
			sweep();
		}
		catch (RuntimeException ex) {
			log.error("Ask AI index recovery at startup failed", ex);
		}
	}

	/** Deletes the index and chunks of every analysis that is not COMPLETED (interrupted or failed ones). */
	void purgeUnfinished() {
		List<UUID> all = indexes.findAllAnalysisIds();
		Set<UUID> orphans = new HashSet<>(all);
		orphans.removeAll(analyses.completedAmong(all));
		for (UUID analysisId : orphans) {
			chunks.deleteByAnalysis(analysisId);
		}
		if (!orphans.isEmpty()) {
			indexes.deleteByAnalysisIdIn(orphans);
			log.info("Dropped the Ask AI index of {} analyses that did not complete", orphans.size());
		}
	}

	/** Submits every INDEXING row of a completed analysis that has no queued or running job. */
	@Scheduled(fixedDelayString = "${archyra.ai.index.sweep-interval:60s}",
			initialDelayString = "${archyra.ai.index.sweep-interval:60s}")
	void sweep() {
		for (AiIndex index : indexes.findByStatus(IndexStatus.INDEXING)) {
			UUID analysisId = index.getAnalysisId();
			if (!jobs.isInFlight(analysisId) && analyses.isCompleted(analysisId) && jobs.submit(analysisId)) {
				log.info("Resumed embedding of analysis {}", analysisId);
			}
		}
	}
}
