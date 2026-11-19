package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.RelationshipType;

/**
 * One relationship next to a seed node, with the node at its other end.
 *
 * @param seedId   the node the relationship was read for
 * @param sourceId where the relationship starts (the seed or {@code other})
 * @param targetId where it ends
 * @param other    the node at the other end: declared, not external and not test code
 */
public record NeighborEdge(String seedId, RelationshipType type, String sourceId, String targetId, NodeInfo other) {
}
