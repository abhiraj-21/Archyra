package com.abhiraj.archyra.parsing;

import java.util.Optional;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;

/**
 * Language-specific rules for turning the module specifier of an import into a module of the
 * repository. Everything else in {@link ReferenceResolver} is language-neutral, so a new language
 * with unusual import semantics only needs an implementation of this interface.
 */
public interface ModuleResolver {

	boolean supports(Language language);

	/**
	 * Resolves {@code module} as written in {@code from}, at {@code relativeLevel}, to a module of the
	 * repository. Empty when it is not part of the repository (a third-party or standard library
	 * module).
	 */
	Optional<ModuleRef> resolve(FileIR from, String module, int relativeLevel, ModuleIndex index);

	/**
	 * Whether imports name types (Java, C#): an unresolved import then becomes an external Class
	 * node. Otherwise (Python, JS, Go) it becomes an external Module node.
	 */
	default boolean importsTypes() {
		return false;
	}

	/**
	 * Whether the specifier is a relative path that must resolve inside the repository: if it does
	 * not, it is not an external package but a broken or generated file, so it is ignored.
	 */
	default boolean isRelative(String module, int relativeLevel) {
		return relativeLevel > 0;
	}
}
