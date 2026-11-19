package com.abhiraj.archyra.ir;

import java.util.List;

/**
 * A method, constructor or free-standing function.
 *
 * @param line              1-based first line
 * @param endLine           1-based last line (inclusive), at least {@code line}
 * @param returnType        null for constructors, void and primitive returns
 * @param locals            local variables declared in the body (used to type call receivers)
 * @param instantiatedTypes types created with {@code new} in the body
 */
public record MethodIR(
		String name,
		boolean constructor,
		int line,
		int endLine,
		List<VariableIR> parameters,
		TypeRefIR returnType,
		List<VariableIR> locals,
		List<CallIR> calls,
		List<TypeRefIR> instantiatedTypes) {

	public MethodIR {
		parameters = List.copyOf(parameters);
		locals = List.copyOf(locals);
		calls = List.copyOf(calls);
		instantiatedTypes = List.copyOf(instantiatedTypes);
	}
}
