package com.abhiraj.archyra.ingestion;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.abhiraj.archyra.ir.Language;

/** Detects a file's language from its name: the file name itself first, then its extension. */
final class LanguageDetector {

	private static final Map<String, Language> BY_FILE_NAME = Map.of(
			"dockerfile", Language.DOCKERFILE,
			"containerfile", Language.DOCKERFILE);

	private static final Map<String, Language> BY_EXTENSION = Map.ofEntries(
			Map.entry("java", Language.JAVA),
			Map.entry("kt", Language.KOTLIN),
			Map.entry("kts", Language.KOTLIN),
			Map.entry("py", Language.PYTHON),
			Map.entry("pyi", Language.PYTHON),
			Map.entry("ipynb", Language.JUPYTER),
			Map.entry("js", Language.JAVASCRIPT),
			Map.entry("jsx", Language.JAVASCRIPT),
			Map.entry("mjs", Language.JAVASCRIPT),
			Map.entry("cjs", Language.JAVASCRIPT),
			Map.entry("ts", Language.TYPESCRIPT),
			Map.entry("tsx", Language.TYPESCRIPT),
			Map.entry("mts", Language.TYPESCRIPT),
			Map.entry("cts", Language.TYPESCRIPT),
			Map.entry("vue", Language.VUE),
			Map.entry("svelte", Language.SVELTE),
			Map.entry("go", Language.GO),
			Map.entry("rs", Language.RUST),
			Map.entry("cs", Language.CSHARP),
			Map.entry("php", Language.PHP),
			Map.entry("phtml", Language.PHP),
			Map.entry("rb", Language.RUBY),
			Map.entry("rake", Language.RUBY),
			Map.entry("gemspec", Language.RUBY),
			Map.entry("sol", Language.SOLIDITY),
			Map.entry("swift", Language.SWIFT),
			Map.entry("dart", Language.DART),
			Map.entry("c", Language.C),
			Map.entry("h", Language.C),
			Map.entry("cc", Language.CPP),
			Map.entry("cpp", Language.CPP),
			Map.entry("cxx", Language.CPP),
			Map.entry("hpp", Language.CPP),
			Map.entry("hh", Language.CPP),
			Map.entry("scala", Language.SCALA),
			Map.entry("r", Language.R),
			Map.entry("jl", Language.JULIA),
			Map.entry("sh", Language.SHELL),
			Map.entry("bash", Language.SHELL),
			Map.entry("zsh", Language.SHELL),
			Map.entry("sql", Language.SQL),
			Map.entry("tf", Language.HCL),
			Map.entry("tfvars", Language.HCL),
			Map.entry("html", Language.HTML),
			Map.entry("htm", Language.HTML),
			Map.entry("css", Language.CSS),
			Map.entry("scss", Language.CSS),
			Map.entry("sass", Language.CSS),
			Map.entry("less", Language.CSS));

	private LanguageDetector() {
	}

	static Optional<Language> detect(String fileName) {
		String lower = fileName.toLowerCase(Locale.ROOT);
		if (isGeneratedOrMinified(lower)) {
			return Optional.empty();
		}
		Language byName = BY_FILE_NAME.get(lower);
		if (byName != null) {
			return Optional.of(byName);
		}
		if (lower.startsWith("dockerfile.") || lower.endsWith(".dockerfile")) {
			return Optional.of(Language.DOCKERFILE);
		}
		int dot = lower.lastIndexOf('.');
		if (dot < 0 || dot == lower.length() - 1) {
			return Optional.empty();
		}
		return Optional.ofNullable(BY_EXTENSION.get(lower.substring(dot + 1)));
	}

	/** Minified bundles and type declaration stubs are noise, not source. */
	private static boolean isGeneratedOrMinified(String lowerCaseName) {
		return lowerCaseName.contains(".min.") || lowerCaseName.endsWith(".bundle.js") || lowerCaseName.endsWith(".d.ts");
	}
}
