package com.abhiraj.archyra.ai.retrieval;

/** Why an item is in the context. Declaration order is the rank: earlier items are filled into the budget first. */
public enum Priority {
	/** Code of a node the question names (a type with a few of its methods, a method, the declarations of a file). */
	NAME_MATCH,
	/** A relationship from the graph, or a note that a node has more of them than listed. */
	FACT,
	/** A chunk whose vector is close to the question. */
	VECTOR,
	/** Code of a node next to a seed in the graph. */
	NEIGHBOR,
	/** Code of a node the previous answer cited, so that follow-ups resolve. */
	STICKY
}
