package com.abhiraj.archyra.codebase;

import java.util.List;

/**
 * Response body of {@code GET /api/v1/analyses/{analysisId}/files}.
 *
 * @param items         the files of this page, sorted by path
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements files that match the filters, over all pages
 */
public record FilePageResponse(List<FileSummary> items, int page, int size, long totalElements, long totalPages) {
}
