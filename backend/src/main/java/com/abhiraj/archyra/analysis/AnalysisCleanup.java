package com.abhiraj.archyra.analysis;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.graph.GraphBuilder;

import lombok.RequiredArgsConstructor;

/**
 * Removes an analysis completely. The graph goes first: if that fails the row stays, so a later retry still finds
 * it and no graph is left without its row.
 */
@Component
@RequiredArgsConstructor
class AnalysisCleanup {

	private final AnalysisRepository analysisRepository;
	private final AnalysisMetadataRepository metadataRepository;
	private final GraphBuilder graphBuilder;

	void delete(Analysis analysis) {
		graphBuilder.delete(analysis.getId().toString());
		metadataRepository.deleteByAnalysisId(analysis.getId());
		analysisRepository.delete(analysis);
	}
}
