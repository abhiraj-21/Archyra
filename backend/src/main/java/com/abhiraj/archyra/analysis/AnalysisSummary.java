package com.abhiraj.archyra.analysis;

import java.time.Instant;

/** One analysis in {@code GET /api/v1/analyses}. */
public record AnalysisSummary(String analysisId, String repositoryUrl, AnalysisStatus status, int progress, Instant createdAt) {

	static AnalysisSummary from(Analysis analysis) {
		return new AnalysisSummary(analysis.getId().toString(), analysis.getRepositoryUrl(), analysis.getStatus(),
				analysis.getProgress(), analysis.getCreatedAt());
	}
}
