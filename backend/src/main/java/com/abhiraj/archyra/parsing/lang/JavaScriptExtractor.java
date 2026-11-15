package com.abhiraj.archyra.parsing.lang;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ir.Language;

/** JavaScript (and JSX, which the JavaScript grammar parses). */
@Component
public class JavaScriptExtractor extends EcmaScriptExtractor {

	@Override
	public boolean supports(Language language) {
		return language == Language.JAVASCRIPT;
	}

	@Override
	protected Language language() {
		return Language.JAVASCRIPT;
	}

	@Override
	protected String grammar() {
		return "javascript";
	}
}
