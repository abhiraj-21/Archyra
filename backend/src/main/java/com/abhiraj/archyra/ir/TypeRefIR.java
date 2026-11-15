package com.abhiraj.archyra.ir;

import java.util.List;

/**
 * A type as written in source, reduced to base names: {@code Map<String, List<User>>} becomes name
 * {@code Map} with type arguments {@code [String, List, User]}. Primitives keep their keyword as name.
 */
public record TypeRefIR(String name, List<String> typeArguments) {

	public TypeRefIR {
		typeArguments = List.copyOf(typeArguments);
	}

	public static TypeRefIR of(String name) {
		return new TypeRefIR(name, List.of());
	}
}
