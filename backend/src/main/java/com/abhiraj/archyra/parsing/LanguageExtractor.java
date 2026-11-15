package com.abhiraj.archyra.parsing;

import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;

/**
 * Turns one source file of one language into IR. Adding a language means adding an implementation;
 * nothing downstream of the IR changes.
 */
public interface LanguageExtractor {

	boolean supports(Language language);

	/**
	 * Extracts the IR of {@code file}. Syntax errors must not throw: extract whatever is
	 * recognisable.
	 */
	FileIR extract(SourceFile file);
}
