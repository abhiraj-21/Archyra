package com.abhiraj.archyra.ir;

import java.util.List;

/**
 * A class, interface, struct, trait, enum, record, object or module declaration.
 *
 * @param name            simple name; for an {@link Declaration#EXTENSION} the name of the extended type
 * @param qualifiedName   module or namespace, enclosing types and name, e.g. {@code com.foo.Outer.Inner}
 * @param declaration     see {@link Declaration}
 * @param extendsTypes    superclass of a class, or the extended interfaces of an interface
 * @param implementsTypes interfaces, traits and mixins a type implements
 * @param nestedTypes     types declared inside this one
 */
public record TypeIR(
		TypeKind kind,
		String name,
		String qualifiedName,
		int line,
		Declaration declaration,
		List<TypeRefIR> extendsTypes,
		List<TypeRefIR> implementsTypes,
		List<VariableIR> fields,
		List<MethodIR> methods,
		List<TypeIR> nestedTypes) {

	public TypeIR {
		extendsTypes = List.copyOf(extendsTypes);
		implementsTypes = List.copyOf(implementsTypes);
		fields = List.copyOf(fields);
		methods = List.copyOf(methods);
		nestedTypes = List.copyOf(nestedTypes);
	}

	/** A regular, single declaration. */
	public TypeIR(TypeKind kind, String name, String qualifiedName, int line, List<TypeRefIR> extendsTypes,
			List<TypeRefIR> implementsTypes, List<VariableIR> fields, List<MethodIR> methods,
			List<TypeIR> nestedTypes) {
		this(kind, name, qualifiedName, line, Declaration.FULL, extendsTypes, implementsTypes, fields, methods,
				nestedTypes);
	}
}
