package com.abhiraj.archyra.ai.retrieval;

import java.util.List;

import com.abhiraj.archyra.ir.NodeType;

/**
 * A chunk of working code.
 *
 * @param priority   why it is here
 * @param nodeId     the graph node the chunk belongs to
 * @param type       the node's type (a type header is a CLASS or INTERFACE)
 * @param file       repository-relative path
 * @param similarity cosine similarity to the question for a vector hit, otherwise 0
 */
public record CodeItem(Priority priority, String nodeId, NodeType type, String qualifiedName, String file, int startLine,
		int endLine, String text, double similarity) implements ContextItem {

	@Override
	public List<String> nodeIds() {
		return List.of(nodeId);
	}

	@Override
	public int estimatedTokens() {
		return ContextItem.estimateTokens(text) + ContextItem.estimateTokens(type + " " + qualifiedName + " " + file + ":" + startLine + "-" + endLine);
	}

	/** Same chunk, for removing duplicates across priorities. */
	String key() {
		return nodeId + "@" + startLine;
	}
}
