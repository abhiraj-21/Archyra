package com.abhiraj.archyra.ai.index;

/**
 * A stored chunk as retrieval reads it.
 *
 * @param similarity cosine similarity to the query (1 is identical) for a vector search; 0 when found by node id
 */
public record FoundChunk(String nodeId, ChunkKind kind, String file, int startLine, int endLine, String content,
		double similarity) {
}
