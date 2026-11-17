package com.abhiraj.archyra.analysis;

import java.util.List;

/**
 * Response body of {@code GET /api/v1/analyses}: the caller's own analyses, newest first.
 *
 * @param page zero-based page number
 * @param size requested page size
 */
public record AnalysisPageResponse(List<AnalysisSummary> items, int page, int size, long totalElements, long totalPages) {
}
