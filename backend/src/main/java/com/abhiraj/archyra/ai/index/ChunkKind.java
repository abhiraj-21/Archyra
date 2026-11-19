package com.abhiraj.archyra.ai.index;

/** What a code chunk covers. */
public enum ChunkKind {
	/** A type's signature, annotations and field declarations, up to its first member. */
	TYPE_HEADER,
	METHOD,
	FUNCTION
}
