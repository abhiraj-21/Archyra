package com.abhiraj.archyra.ir;

/**
 * Kinds of graph relationship. Names are used verbatim as Neo4j relationship types and as API values.
 */
public enum RelationshipType {
	IMPORTS,
	CONTAINS,
	CALLS,
	DEPENDS_ON,
	EXTENDS,
	IMPLEMENTS,
	REFERENCES
}
