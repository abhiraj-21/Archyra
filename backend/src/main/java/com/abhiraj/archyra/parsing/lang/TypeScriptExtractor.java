package com.abhiraj.archyra.parsing.lang;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.Language;

/** TypeScript; {@code .tsx} files use the TSX grammar so JSX parses. */
@Component
public class TypeScriptExtractor extends EcmaScriptExtractor {

	@Override
	public boolean supports(Language language) {
		return language == Language.TYPESCRIPT;
	}

	@Override
	protected Language language() {
		return Language.TYPESCRIPT;
	}

	@Override
	protected String grammar() {
		return "typescript";
	}

	@Override
	protected String grammarFor(SourceFile file) {
		return file.relativePath().endsWith(".tsx") ? "tsx" : "typescript";
	}
}
