package com.abhiraj.archyra.security;

/**
 * Capabilities that need the PREMIUM plan. {@code displayName} is what the 403 message says, e.g.
 * "Expert graph requires Archyra Premium."
 */
public enum Feature {
	INTERMEDIATE_GRAPH("Intermediate graph"),
	EXPERT_GRAPH("Expert graph"),
	INTERACTIVE_GRAPH("Interactive graph exploration"),
	NODE_EXPANSION("Node expansion"),
	NODE_RELATIONSHIPS("Node relationships"),
	CLASS_METHODS("Class method drill-down"),
	METHOD_DEPENDENCIES("Method dependencies");

	private final String displayName;

	Feature(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
