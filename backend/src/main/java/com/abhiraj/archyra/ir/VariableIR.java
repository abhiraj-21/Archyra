package com.abhiraj.archyra.ir;

/** A field, parameter or local variable. {@code type} is null when no type is written ({@code var}). */
public record VariableIR(String name, TypeRefIR type) {
}
