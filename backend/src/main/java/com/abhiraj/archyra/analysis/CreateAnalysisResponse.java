package com.abhiraj.archyra.analysis;

/**
 * Response body of {@code POST /api/v1/analyses}.
 */
public record CreateAnalysisResponse(String analysisId, AnalysisStatus status) {
}
