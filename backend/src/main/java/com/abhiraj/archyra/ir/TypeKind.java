package com.abhiraj.archyra.ir;

/**
 * Declaration kind of a type-like construct. {@link #interfaceLike()} kinds become Interface nodes,
 * everything else a Class node; the original kind is kept as the node's {@code kind} property.
 */
public enum TypeKind {
	CLASS,
	INTERFACE,
	ENUM,
	RECORD,
	ANNOTATION,
	/** Go, Rust, C# structs. */
	STRUCT,
	/** Rust trait, PHP trait. */
	TRAIT,
	/** Kotlin object, Solidity library, singleton-like declarations. */
	OBJECT,
	/** Ruby module used as a mixin namespace. */
	MODULE;

	public boolean interfaceLike() {
		return this == INTERFACE || this == ANNOTATION || this == TRAIT;
	}
}
