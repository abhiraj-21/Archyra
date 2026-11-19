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

	/**
	 * This chunk cut so that it costs at most {@code maxTokens}, ending with a marker, or itself when it already fits; null when
	 * not even a short head would fit. A README is cut at a line end so a sentence is not left in the middle of a word.
	 */
	CodeItem truncatedTo(int maxTokens) {
		if (estimatedTokens() <= maxTokens) {
			return this;
		}
		int label = ContextItem.estimateTokens(type + " " + qualifiedName + " " + file + ":" + startLine + "-" + endLine);
		int available = maxTokens - label - 2;
		if (available < RetrievedContext.MIN_DOC_TOKENS) {
			return null;
		}
		int chars = (int) (available / 1.2 * 4) - TRUNCATION_MARKER.length();
		String head = text.substring(0, Math.max(0, Math.min(chars, text.length())));
		int lineEnd = head.lastIndexOf('\n');
		if (lineEnd > head.length() / 2) {
			head = head.substring(0, lineEnd);
		}
		String cut = head.stripTrailing() + TRUNCATION_MARKER;
		return new CodeItem(priority, nodeId, type, qualifiedName, file, startLine, Math.max(startLine, startLine + (int) cut.lines().count() - 1), cut, similarity);
	}

	private static final String TRUNCATION_MARKER = "\n... [truncated]";

	/**
	 * Same chunk, for removing duplicates across priorities. The file is part of it: the README and the manifest chunks belong to
	 * the same node and both start at line 1, and must not be taken for one chunk.
	 */
	String key() {
		return nodeId + "@" + startLine + "@" + file;
	}
}
