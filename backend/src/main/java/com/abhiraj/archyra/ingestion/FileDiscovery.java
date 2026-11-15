package com.abhiraj.archyra.ingestion;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ir.Language;

import lombok.RequiredArgsConstructor;

/**
 * Walks a cloned repository, skipping vendored code and build output, enforcing the repository
 * limits and detecting each file's language. Symbolic links are never followed.
 */
@Component
@RequiredArgsConstructor
public class FileDiscovery {

	private static final Set<String> IGNORED_DIRECTORIES = Set.of(
			".git", ".idea", ".vscode", ".gradle", ".mvn",
			"node_modules", "bower_components", "vendor", "target", "build", "dist", "out",
			"__pycache__", ".venv", "venv");

	private static final long NOTEBOOK_SIZE_FACTOR = 10;

	private final IngestionProperties properties;

	/** Notebooks are mostly embedded outputs (images, tables), so they get a larger allowance than code. */
	private long sizeLimit(Language language) {
		return language == Language.JUPYTER ? properties.maxSourceFileBytes() * NOTEBOOK_SIZE_FACTOR
				: properties.maxSourceFileBytes();
	}

	public DiscoveredFiles discover(Path root) {
		List<SourceFile> sourceFiles = new ArrayList<>();
		List<String> allPaths = new ArrayList<>();
		Map<Language, Integer> languageFiles = new EnumMap<>(Language.class);
		int[] totalFiles = {0};
		long[] totalBytes = {0};

		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					if (!dir.equals(root) && IGNORED_DIRECTORIES.contains(dir.getFileName().toString())) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (!attrs.isRegularFile()) {
						return FileVisitResult.CONTINUE; // symlinks and special files
					}
					totalFiles[0]++;
					totalBytes[0] += attrs.size();
					if (totalFiles[0] > properties.maxFiles()) {
						throw new AnalysisFailureException(
								"The repository has more than " + properties.maxFiles() + " files, which is the limit");
					}
					if (totalBytes[0] > properties.maxRepositoryBytes()) {
						throw new AnalysisFailureException("The repository is larger than "
								+ properties.maxRepositoryBytes() / (1024 * 1024) + " MB, which is the limit");
					}

					String relative = root.relativize(file).toString().replace('\\', '/');
					allPaths.add(relative);
					Optional<Language> language = LanguageDetector.detect(file.getFileName().toString());
					if (language.isPresent() && attrs.size() <= sizeLimit(language.get())) {
						sourceFiles.add(new SourceFile(relative, file, language.get()));
						languageFiles.merge(language.get(), 1, Integer::sum);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			throw new AnalysisFailureException("The repository files could not be read", ex);
		}

		sourceFiles.sort(Comparator.comparing(SourceFile::relativePath));
		allPaths.sort(Comparator.naturalOrder());
		return new DiscoveredFiles(List.copyOf(sourceFiles), languageFiles, totalFiles[0], List.copyOf(allPaths));
	}
}
