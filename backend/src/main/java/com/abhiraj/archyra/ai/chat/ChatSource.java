package com.abhiraj.archyra.ai.chat;

import com.abhiraj.archyra.ir.NodeType;

/**
 * A node an answer rests on. Every source is a node of the analysis, verified in Neo4j before the answer is returned.
 *
 * @param ref     the number of the {@code [n]} marker in the answer that first cited it
 * @param excerpt the first lines of the chunk the model saw, or null for a node known only from a graph fact
 */
public record ChatSource(int ref, NodeType type, String id, String name, String file, Integer line, Integer endLine, String excerpt) {
}
