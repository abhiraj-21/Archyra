package com.abhiraj.archyra.ingestion;

import java.nio.file.Path;

import com.abhiraj.archyra.ir.Language;

/**
 * A source file found in a cloned repository.
 *
 * @param relativePath repository-relative path with forward slashes (used in the graph)
 * @param absolutePath location on disk inside the temporary clone
 */
public record SourceFile(String relativePath, Path absolutePath, Language language) {
}
