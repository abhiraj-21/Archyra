package com.abhiraj.archyra.ir;

/**
 * A resolved graph node.
 *
 * @param id            stable id exposed as {@code nodeId}, e.g. {@code class:com.foo.UserService}
 * @param file          repository-relative path of the declaring file; null for external nodes
 * @param line          1-based declaration line; null when unknown
 * @param endLine       1-based last line of the declaration (inclusive); null for external nodes and for nodes
 *                      that are not declarations (repository, module, file). For a type declared in several pieces
 *                      (C# partial, Go receivers, Rust impl) it is the end of the first piece
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
		Integer endLine,
		String kind,
		boolean external) {
}
