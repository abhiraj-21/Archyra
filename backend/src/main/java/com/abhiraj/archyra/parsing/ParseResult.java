package com.abhiraj.archyra.parsing;

import java.util.List;

import com.abhiraj.archyra.ir.RepositoryIR;

/**
 * Output of the parsing stage.
 *
 * @param skippedFiles source files in a language without an extractor
 */
public record ParseResult(RepositoryIR repository, List<ParseFailure> failures, int skippedFiles) {
}
