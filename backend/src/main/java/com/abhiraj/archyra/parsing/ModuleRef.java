package com.abhiraj.archyra.parsing;

import java.util.List;

/**
 * A module of the repository that an import resolves to.
 *
 * @param module the module name ({@code FileIR.moduleName})
 * @param files  the exact files, when the import names a file (JS, Ruby, Solidity); empty when the
 *               import names a module that may span several files (Java package, Go package)
 */
public record ModuleRef(String module, List<String> files) {

	public ModuleRef {
		files = List.copyOf(files);
	}

	public static ModuleRef ofModule(String module) {
		return new ModuleRef(module, List.of());
	}
}
