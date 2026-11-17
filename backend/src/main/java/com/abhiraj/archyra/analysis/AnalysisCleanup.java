package com.abhiraj.archyra.analysis;

import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.abhiraj.archyra.graph.GraphBuilder;

import lombok.RequiredArgsConstructor;

/**
 * Removes an analysis completely. The graph goes first: if that fails the row stays, so a later retry still finds
 * it and no graph is left without its row. A free user gets back the credit the analysis used (see
 * {@link AnalysisRegistrar#deleteAndCredit}), whoever asked for the deletion: the user, or a completed re-run that
 * replaces it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AnalysisCleanup {

	private final AnalysisRepository analysisRepository;
	private final AnalysisMetadataRepository metadataRepository;
	private final GraphBuilder graphBuilder;
	private final AnalysisRegistrar registrar;

	void delete(Analysis analysis) {
		graphBuilder.delete(analysis.getId().toString());
		metadataRepository.deleteByAnalysisId(analysis.getId());
		registrar.deleteAndCredit(analysis);
	}

	/**
	 * After a re-run has completed, removes the analysis it replaces. It never throws and never touches anything but a
	 * finished analysis of the same owner: the new analysis is already COMPLETED and must stay so, and if the old one
	 * cannot be removed now it simply stays (the user can delete it).
	 */
	void deleteReplaced(Analysis completed) {
		UUID replacedId = completed.getReplacesAnalysisId();
		if (replacedId == null || replacedId.equals(completed.getId())) {
			return;
		}
		try {
			analysisRepository.findById(replacedId)
					.filter(old -> completed.getOwnerId().equals(old.getOwnerId()) && old.isFinished())
					.ifPresent(old -> {
						delete(old);
						log.info("Analysis {} replaced analysis {}, which was deleted", completed.getId(), old.getId());
					});
		}
		catch (RuntimeException ex) {
			log.warn("Analysis {} completed, but the analysis it replaces ({}) could not be deleted", completed.getId(), replacedId, ex);
		}
	}
}
