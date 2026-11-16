package com.abhiraj.archyra.graph;

import java.util.List;

/** Response body of {@code GET /api/v1/analyses/{analysisId}/graph}. */
public record GraphResponse(List<GraphNode> nodes, List<GraphEdge> edges) {
}
