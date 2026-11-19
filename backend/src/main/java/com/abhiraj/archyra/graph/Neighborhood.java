package com.abhiraj.archyra.graph;

import java.util.List;
import java.util.Map;

/**
 * The one-hop neighbourhood of a set of seed nodes.
 *
 * @param edges  at most the requested number per seed, architectural relationships first
 * @param totals how many qualifying neighbours each seed has in all, so a caller can say that a list is cut
 */
public record Neighborhood(List<NeighborEdge> edges, Map<String, Integer> totals) {

	public Neighborhood {
		edges = List.copyOf(edges);
		totals = Map.copyOf(totals);
	}

	public static Neighborhood empty() {
		return new Neighborhood(List.of(), Map.of());
	}
}
