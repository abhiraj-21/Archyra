package com.abhiraj.archyra.ai.retrieval;

import java.util.List;

import com.abhiraj.archyra.ir.NodeType;

/**
 * One piece of context for a question: code, or a fact from the graph. The prompt numbers the items {@code [1]..[N]} and
 * keeps a private map from number to node ids, so the model never sees or invents a node id.
 */
public sealed interface ContextItem permits CodeItem, FactItem {

	Priority priority();

	/** The graph nodes this item stands for: what a citation of it makes a source. */
	List<String> nodeIds();

	/** Estimated size in tokens, including its label, for fitting the context into a provider's budget. */
	int estimatedTokens();

	/** Characters per token, with a safety margin, because the providers' tokenizers differ and an overflow can never succeed. */
	static int estimateTokens(String text) {
		return (int) Math.ceil(text.length() / 4.0 * 1.2) + 2;
	}

	/** The node type an id says ({@code class:...} is a CLASS), or null for an id that is not one of ours. */
	static NodeType typeOf(String nodeId) {
		int colon = nodeId.indexOf(':');
		if (colon <= 0) {
			return null;
		}
		String prefix = nodeId.substring(0, colon);
		for (NodeType type : NodeType.values()) {
			if (type.name().toLowerCase().equals(prefix)) {
				return type;
			}
		}
		return null;
	}

	/** The qualified name an id carries after its type prefix. */
	static String qualifiedNameOf(String nodeId) {
		int colon = nodeId.indexOf(':');
		return colon < 0 ? nodeId : nodeId.substring(colon + 1);
	}
}
