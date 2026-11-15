package com.abhiraj.archyra.ir;

import java.util.List;

/**
 * Everything extracted from one source file.
 *
 * @param path        repository-relative path with forward slashes
 * @param moduleName  what qualifies the symbols declared here and what imports refer to: the Java
 *                    package, the Python or JS module path, the Go package directory, the C# namespace.
 *                    Several files share one when the language declares symbols per package
 * @param packageName grouping used for the Module node (package, namespace or directory); empty for
 *                    the root
 * @param functions   free-standing functions (languages that have them; empty for Java)
 */
public record FileIR(
		String path,
		Language language,
		String moduleName,
		String packageName,
		List<ImportIR> imports,
		List<TypeIR> types,
		List<MethodIR> functions) {

	public FileIR {
		imports = List.copyOf(imports);
		types = List.copyOf(types);
		functions = List.copyOf(functions);
	}

	/** For package-scoped languages where the module and the grouping are the same name. */
	public FileIR(String path, Language language, String packageName, List<ImportIR> imports, List<TypeIR> types,
			List<MethodIR> functions) {
		this(path, language, packageName, packageName, imports, types, functions);
	}
}
