package com.abhiraj.archyra.ir;

/**
 * Kinds of graph node. Names match the API values ({@code ?nodeType=CLASS}); {@link #label()} is the
 * Neo4j label.
 */
public enum NodeType {
	REPOSITORY,
	MODULE,
	FILE,
	CLASS,
	INTERFACE,
	METHOD,
	FUNCTION;

	/** Neo4j label, e.g. {@code Class}. */
	public String label() {
		String name = name();
		return name.charAt(0) + name.substring(1).toLowerCase();
	}
}
