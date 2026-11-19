package com.abhiraj.archyra.ai.index;

/** Stored state of an index. "Not indexed" is the absence of a row, not a value. */
public enum IndexStatus {
	INDEXING, READY, FAILED, EMPTY
}
