package com.abhiraj.archyra.ai.index;

/**
 * One chunk ready to be stored. The lines are the 1-based, inclusive range the text was cut from.
 *
 * @param nodeId the graph node the chunk belongs to
 */
public record ChunkData(String nodeId, ChunkKind kind, String file, int startLine, int endLine, String content) {
}
