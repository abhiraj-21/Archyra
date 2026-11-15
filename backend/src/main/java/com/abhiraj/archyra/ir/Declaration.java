package com.abhiraj.archyra.ir;

/**
 * How a {@link TypeIR} relates to the type of the same qualified name.
 *
 * <ul>
 * <li>{@link #FULL}: the declaration. A second FULL declaration of the same name is ignored.</li>
 * <li>{@link #PARTIAL}: one of several declarations that are merged (C# {@code partial} classes).</li>
 * <li>{@link #EXTENSION}: members added to a type declared elsewhere: Go methods with a receiver,
 * Rust {@code impl} blocks. Dropped when the type is not part of the repository.</li>
 * </ul>
 */
public enum Declaration {
	FULL,
	PARTIAL,
	EXTENSION
}
