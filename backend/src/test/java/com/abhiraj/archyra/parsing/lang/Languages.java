package com.abhiraj.archyra.parsing.lang;

import java.util.List;

import com.abhiraj.archyra.parsing.LanguageExtractor;
import com.abhiraj.archyra.parsing.ModuleResolver;

/** Test support: the extractors and module resolvers that Spring would register as beans. */
final class Languages {

	private Languages() {
	}

	static List<LanguageExtractor> extractors() {
		PythonExtractor python = new PythonExtractor();
		JavaScriptExtractor javaScript = new JavaScriptExtractor();
		TypeScriptExtractor typeScript = new TypeScriptExtractor();
		return List.of(new JavaExtractor(), python, javaScript, typeScript, new GoExtractor(), new RustExtractor(), new CSharpExtractor(), new PhpExtractor(), new RubyExtractor(), new KotlinExtractor(), new SolidityExtractor(),
				new SingleFileComponentExtractor(javaScript, typeScript), new NotebookExtractor(python));
	}

	static List<ModuleResolver> moduleResolvers() {
		return List.of(new NamespaceModuleResolver(), new PythonModuleResolver(), new JavaScriptModuleResolver(), new GoModuleResolver(), new RustModuleResolver(), new RubyModuleResolver(), new SolidityModuleResolver());
	}
}
