package com.abhiraj.archyra.analysis;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Analyses created before accounts existed have no owner, so nobody may read them. On startup they are
 * deleted from PostgreSQL and Neo4j (a decision recorded in specs/security: delete, do not adopt). The graph
 * goes first; if that fails the row stays and the next startup retries, so no graph is left without its row.
 * Once every new analysis has an owner this finds nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LegacyAnalysisPurger {

	private final AnalysisRepository analysisRepository;
	private final AnalysisCleanup cleanup;

	@EventListener(ApplicationReadyEvent.class)
	void purgeOwnerlessAnalyses() {
		List<Analysis> ownerless = analysisRepository.findByOwnerIdIsNull();
		int deleted = 0;
		for (Analysis analysis : ownerless) {
			try {
				cleanup.delete(analysis);
				deleted++;
			}
			catch (RuntimeException ex) {
				log.error("Could not delete ownerless analysis {}; it will be retried at the next startup",
						analysis.getId(), ex);
			}
		}
		if (!ownerless.isEmpty()) {
			log.warn("Deleted {} of {} analyses that had no owner", deleted, ownerless.size());
		}
	}
}
