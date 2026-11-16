package com.abhiraj.archyra.graph;

/**
 * Totals of an analysed repository's own code (external nodes excluded).
 *
 * @param repositoryName name of the repository, as stored on its {@code Repository} node
 * @param classes        {@code Class} nodes (enums and records included, interfaces not)
 * @param methods        {@code Method} and {@code Function} nodes: every callable declaration, since
 *                       languages without classes have functions only
 */
public record GraphCounts(String repositoryName, long classes, long methods) {
}
