package com.abhiraj.archyra.codebase;

import java.util.List;

/**
 * Response body of {@code GET /api/v1/analyses/{analysisId}/repository}.
 *
 * @param languages every language found, the most used first
 * @param files     files in those languages, including ones that are only counted and not in the graph
 * @param classes   classes of the repository's own code (see {@code GraphCounts})
 * @param methods   methods and functions of the repository's own code (see {@code GraphCounts})
 */
public record RepositoryResponse(String name, List<String> languages, long files, long classes, long methods) {
}
