package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.NodeType;

/**
 * A declared (non-external) node as retrieval needs it. Never carries Neo4j internals.
 *
 * @param parentId the node that contains this one (the file of a type, the type of a method), or null
 */
public record NodeInfo(
		String id,
		NodeType type,
		String name,
		String qualifiedName,
		String file,
		Integer line,
		Integer endLine,
		String parentId) {
}
