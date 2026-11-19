package com.abhiraj.archyra.ai.retrieval;

import java.util.List;

/**
 * One fact of the project context pack, read from data the analysis already holds (never invented): it cites the node it
 * describes, so the number the model writes becomes a source, verified in Neo4j like any other.
 *
 * @param role    what the fact is; also its place in the pack and in the order facts are dropped under a small budget
 * @param text    what the model reads after the number
 * @param nodeIds the nodes a citation of it makes sources: the repository node, a module or a class
 */
public record ProjectItem(Role role, String text, List<String> nodeIds) implements ContextItem {

	/** In rank order. IDENTITY is never dropped. */
	public enum Role {
		IDENTITY, TECHNOLOGIES, COUNTS, CLASS, MODULE
	}

	public ProjectItem {
		nodeIds = List.copyOf(nodeIds);
	}

	@Override
	public Priority priority() {
		return Priority.PROJECT;
	}

	@Override
	public int estimatedTokens() {
		return ContextItem.estimateTokens("FACT: " + text);
	}
}
