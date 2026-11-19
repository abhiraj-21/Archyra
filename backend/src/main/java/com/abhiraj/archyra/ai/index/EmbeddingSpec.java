package com.abhiraj.archyra.ai.index;

/**
 * The embedding model the vectors come from and their size, in one place. The size is also the size of the
 * {@code code_chunks.embedding} column, so changing the model means changing both (and re-indexing).
 */
public final class EmbeddingSpec {

	/** Name stored in {@code ai_index.embedding_model}. */
	public static final String MODEL_NAME = "all-MiniLM-L6-v2";

	/** Output size of {@link #MODEL_NAME}; the column is {@code vector(DIMENSION)}. */
	public static final int DIMENSION = 384;

	private EmbeddingSpec() {
	}
}
