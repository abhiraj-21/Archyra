package com.abhiraj.archyra.ai.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything retrieval found for one question, best first. The prompt is built per provider, and a fallback provider has
 * a smaller context budget, so the same candidates are fitted again with {@link #within}.
 *
 * @param ranked name matches, then facts, vector hits, neighbours and sticky items, each group in a stable order
 */
public record RetrievedContext(List<ContextItem> ranked) {

	public RetrievedContext {
		ranked = List.copyOf(ranked);
	}

	/**
	 * The items that fit {@code tokenBudget}, in rank order. An item that does not fit is skipped and smaller, lower
	 * ranked ones may still be taken, so a few large chunks cannot crowd out the cheap facts.
	 */
	public List<ContextItem> within(int tokenBudget) {
		List<ContextItem> chosen = new ArrayList<>();
		int remaining = tokenBudget;
		for (ContextItem item : ranked) {
			int cost = item.estimatedTokens();
			if (cost <= remaining) {
				chosen.add(item);
				remaining -= cost;
			}
		}
		return chosen;
	}

	/** Total estimated tokens of {@code items}. */
	public static int tokensOf(List<ContextItem> items) {
		return items.stream().mapToInt(ContextItem::estimatedTokens).sum();
	}
}
