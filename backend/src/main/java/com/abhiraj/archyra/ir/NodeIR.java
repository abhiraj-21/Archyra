package com.abhiraj.archyra.ir;

/**
 * A resolved graph node.
 *
 * @param id            stable id exposed as {@code nodeId}, e.g. {@code class:com.foo.UserService}
 * @param file          repository-relative path of the declaring file; null for external nodes
 * @param line          1-based declaration line; null when unknown
 * @param kind          finer-grained declaration kind (CLASS, ENUM, RECORD, ...); null when not applicable
 * @param external      true when the target is not part of the analysed repository
 */
public record NodeIR(
		String id,
		NodeType type,
		String name,
		String qualifiedName,
		String file,
		Integer line,
		String kind,
		boolean external) {
}
