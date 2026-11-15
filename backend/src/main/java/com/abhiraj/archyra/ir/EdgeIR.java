package com.abhiraj.archyra.ir;

/** A resolved, directed relationship between two {@link NodeIR} ids. */
public record EdgeIR(String source, String target, RelationshipType type) {
}
