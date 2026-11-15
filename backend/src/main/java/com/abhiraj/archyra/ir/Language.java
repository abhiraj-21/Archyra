package com.abhiraj.archyra.ir;

/**
 * Languages Archyra recognises by file name. Only some have an extractor (see
 * {@code parsing.lang}); the others are still counted in the language statistics and feed technology
 * detection, but contribute no classes or functions to the graph.
 */
public enum Language {
	// code with an extractor
	JAVA("Java"),
	KOTLIN("Kotlin"),
	PYTHON("Python"),
	JAVASCRIPT("JavaScript"),
	TYPESCRIPT("TypeScript"),
	GO("Go"),
	RUST("Rust"),
	CSHARP("C#"),
	PHP("PHP"),
	RUBY("Ruby"),
	SOLIDITY("Solidity"),
	VUE("Vue"),
	SVELTE("Svelte"),
	JUPYTER("Jupyter Notebook"),

	// code without an extractor yet
	SWIFT("Swift"),
	DART("Dart"),
	C("C"),
	CPP("C++"),
	SCALA("Scala"),
	R("R"),
	JULIA("Julia"),
	SHELL("Shell"),

	// data, markup and infrastructure
	SQL("SQL"),
	HCL("HCL (Terraform)"),
	DOCKERFILE("Dockerfile"),
	HTML("HTML"),
	CSS("CSS");

	private final String displayName;

	Language(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
