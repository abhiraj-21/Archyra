package com.abhiraj.archyra.ai.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything retrieval found for one question, best first. The prompt is built per provider, and a fallback provider has
 * a smaller context budget, so the same candidates are fitted again with {@link #within}.
 *
 * @param ranked name matches, then facts, vector hits, neighbours and sticky items, each group in a stable order; for an
 *               overview question the project context pack and the README come first
 * @param usable whether there is anything in this repository that relates to the question. False means the question is
 *               refused before a provider is called or a question is counted (see {@code ChatService}): a question that
 *               names no node and whose closest chunk is not close enough, however much loosely related text was found
 */
public record RetrievedContext(List<ContextItem> ranked, boolean usable) {

	/** Never fewer than this is worth showing of a README or manifest. */
	static final int MIN_DOC_TOKENS = 80;

	public RetrievedContext {
		ranked = List.copyOf(ranked);
	}

	/** Usable when there is anything at all. */
	public RetrievedContext(List<ContextItem> ranked) {
		this(ranked, !ranked.isEmpty());
	}

	/**
	 * The items that fit {@code tokenBudget}, in rank order. An item that does not fit is skipped and smaller, lower
	 * ranked ones may still be taken, so a few large chunks cannot crowd out the cheap facts.
	 *
	 * <p>An overview context (it has project facts) is fitted differently, so that a small budget loses things in a fixed
	 * order: the README and manifest chunks are truncated first (they get what is left after the facts), then the modules go
	 * (the last in rank first), then the classes, then the technologies and counts; <b>the identity fact is never dropped</b>.
	 * The items come back as identity, manifest, README, the other facts, then any vector hits.
	 */
	public List<ContextItem> within(int tokenBudget) {
		boolean overview = ranked.stream().anyMatch(item -> item.priority() == Priority.PROJECT);
		return overview ? withinOverview(tokenBudget) : withinGeneric(tokenBudget);
	}

	private List<ContextItem> withinGeneric(int tokenBudget) {
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

	private List<ContextItem> withinOverview(int tokenBudget) {
		List<ContextItem> identity = new ArrayList<>();
		List<ContextItem> facts = new ArrayList<>();
		List<ContextItem> docs = new ArrayList<>();
		List<ContextItem> others = new ArrayList<>();
		for (ContextItem item : ranked) {
			if (item instanceof ProjectItem project) {
				(project.role() == ProjectItem.Role.IDENTITY ? identity : facts).add(project);
			}
			else if (item.priority() == Priority.PROJECT_DOCS) {
				docs.add(item);
			}
			else {
				others.add(item);
			}
		}
		List<ContextItem> chosen = new ArrayList<>();
		int remaining = tokenBudget;
		for (ContextItem item : identity) {
			chosen.add(item); // never dropped, even if the budget is absurdly small
			remaining -= item.estimatedTokens();
		}
		List<ContextItem> keptFacts = new ArrayList<>();
		for (ContextItem fact : facts) {
			int cost = fact.estimatedTokens();
			if (cost <= remaining) {
				keptFacts.add(fact);
				remaining -= cost;
			}
		}
		for (ContextItem doc : docs) {
			ContextItem fitted = doc instanceof CodeItem code ? code.truncatedTo(remaining) : null;
			if (fitted != null) {
				chosen.add(fitted);
				remaining -= fitted.estimatedTokens();
			}
		}
		chosen.addAll(keptFacts);
		for (ContextItem other : others) {
			int cost = other.estimatedTokens();
			if (cost <= remaining) {
				chosen.add(other);
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
