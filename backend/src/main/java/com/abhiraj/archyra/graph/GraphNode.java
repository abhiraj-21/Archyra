package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.NodeType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A node of the {@code /graph} response, in the React Flow shape. {@code file} is the declaring file; it is
 * left out for nodes without one (repository, module).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphNode(String id, NodeType type, String name, String file) {
}
