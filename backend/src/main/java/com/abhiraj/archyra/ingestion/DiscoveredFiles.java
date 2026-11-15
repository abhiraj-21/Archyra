package com.abhiraj.archyra.ingestion;

import java.util.List;
import java.util.Map;

import com.abhiraj.archyra.ir.Language;

/**
 * Result of file discovery.
 *
 * @param sourceFiles   files in a recognised language, in a stable (sorted) order
 * @param languageFiles number of files per detected language, whether or not an extractor exists for it
 * @param totalFiles    number of files seen outside ignored directories
 * @param allPaths      repository-relative paths of all of those files (manifests, configuration, infrastructure
 *                      files and sources alike), in a stable order, for technology detection
 */
public record DiscoveredFiles(List<SourceFile> sourceFiles, Map<Language, Integer> languageFiles, int totalFiles,
		List<String> allPaths) {

	public DiscoveredFiles {
		sourceFiles = List.copyOf(sourceFiles);
		allPaths = List.copyOf(allPaths);
	}

	public DiscoveredFiles(List<SourceFile> sourceFiles, Map<Language, Integer> languageFiles, int totalFiles) {
		this(sourceFiles, languageFiles, totalFiles, List.of());
	}
}
