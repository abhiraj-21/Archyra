package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.NodeType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A node of the {@code /graph} response, in the React Flow shape. {@code file} is the declaring file; it is
 * left out for nodes without one (repository, module). {@code test} is always present: true for code declared in a
 * test file (see {@code TestPathClassifier}) and for a module whose files are all tests.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphNode(String id, NodeType type, String name, String file, boolean test) {
}
