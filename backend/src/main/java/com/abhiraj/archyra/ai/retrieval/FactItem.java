package com.abhiraj.archyra.ai.retrieval;

import java.util.ArrayList;
import java.util.List;

import com.abhiraj.archyra.ir.RelationshipType;

/**
 * A fact read from the graph: {@code UserController DEPENDS_ON UserService}. It stands for both end nodes, so citing it
 * cites both. A summary fact (no relationship, no target) says that a node has more relationships than were listed,
 * so the model can say that a list is partial instead of presenting it as complete.
 *
 * @param text         what the model reads after the number
 * @param sourceId     where the relationship starts (the node a summary is about)
 * @param relationship null for a summary
 * @param targetId     where it ends; null for a summary
 */
public record FactItem(String text, String sourceId, RelationshipType relationship, String targetId) implements ContextItem {

	public static FactItem relationship(String sourceId, String sourceName, RelationshipType type, String targetId, String targetName) {
		return new FactItem(sourceName + " " + type + " " + targetName, sourceId, type, targetId);
	}

	public static FactItem partialList(String nodeId, String name) {
		return new FactItem(name + " has more relationships than are listed here", nodeId, null, null);
	}

	public boolean isSummary() {
		return relationship == null;
	}

	@Override
	public Priority priority() {
		return Priority.FACT;
	}

	@Override
	public List<String> nodeIds() {
		List<String> ids = new ArrayList<>(2);
		ids.add(sourceId);
		if (targetId != null) {
			ids.add(targetId);
		}
		return ids;
	}

	@Override
	public int estimatedTokens() {
		return ContextItem.estimateTokens("FACT: " + text);
	}
}
