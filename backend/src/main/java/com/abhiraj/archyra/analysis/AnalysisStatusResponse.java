package com.abhiraj.archyra.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body of {@code GET /api/v1/analyses/{analysisId}}. {@code progress} is a percentage from 0 to 100.
 * {@code failureReason} is set only for {@link AnalysisStatus#FAILED}; for every other status it is null and
 * left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisStatusResponse(String analysisId, AnalysisStatus status, int progress, String failureReason) {

	static AnalysisStatusResponse from(Analysis analysis) {
		String failureReason = analysis.getStatus() == AnalysisStatus.FAILED ? analysis.getFailureReason() : null;
		return new AnalysisStatusResponse(analysis.getId().toString(), analysis.getStatus(),
				analysis.getProgress(), failureReason);
	}
}
