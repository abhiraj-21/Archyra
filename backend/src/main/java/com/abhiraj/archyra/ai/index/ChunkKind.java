package com.abhiraj.archyra.ai.index;

/**
 * What a chunk covers. Stored as a string: add a value here and widen the CHECK of an existing database
 * ({@code CodeChunkSchemaUpgrade}); nothing may switch exhaustively on these values.
 */
public enum ChunkKind {
	/** A type's signature, annotations and field declarations, up to its first member. */
	TYPE_HEADER,
	METHOD,
	FUNCTION,
	/** The root README, cleaned (no badges, images or HTML). Belongs to the repository node. */
	README,
	/** The name, description and keywords the root manifests declare (pom.xml, package.json, ...). Belongs to the repository node. */
	MANIFEST;

	/** A chunk about the repository as a whole: it is searchable like any other, but never a seed for graph expansion. */
	public boolean isProjectDoc() {
		return this == README || this == MANIFEST;
	}
}
