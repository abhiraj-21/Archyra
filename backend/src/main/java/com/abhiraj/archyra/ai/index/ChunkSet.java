package com.abhiraj.archyra.ai.index;

import java.util.List;

/**
 * What was captured from a repository.
 *
 * @param chunks in file order, then line order
 * @param capped true when the repository had more chunks than {@code archyra.ai.chunk.max-per-analysis}
 */
public record ChunkSet(List<ChunkData> chunks, boolean capped) {

	public ChunkSet {
		chunks = List.copyOf(chunks);
	}
}
