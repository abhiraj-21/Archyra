package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.RelationshipType;

/** An edge of the {@code /graph} response; {@code source} and {@code target} are node ids. */
public record GraphEdge(String source, String target, RelationshipType type) {
}
