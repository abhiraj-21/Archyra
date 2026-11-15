package com.abhiraj.archyra.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ir.Language;

class FileDiscoveryTest {

	@TempDir
	Path root;

	private FileDiscovery discovery(int maxFiles, long maxRepositoryBytes, long maxSourceFileBytes) {
		return new FileDiscovery(new IngestionProperties(maxFiles, maxRepositoryBytes, maxSourceFileBytes,
				Duration.ofMinutes(1)));
	}

	private FileDiscovery discovery() {
		return discovery(1000, 10_000_000, 1_000_000);
	}

	private void write(String relativePath, String content) throws IOException {
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	@Test
	void detectsLanguagesFromExtensionsAndReturnsRelativeForwardSlashPaths() throws IOException {
		write("src/Main.java", "class Main {}");
		write("tools/run.py", "print(1)");
		write("web/app.TSX", "export {}");
		write("README.md", "# readme");
		write("Makefile", "all:");

		DiscoveredFiles result = discovery().discover(root);

		assertThat(result.sourceFiles()).extracting(SourceFile::relativePath)
				.containsExactly("src/Main.java", "tools/run.py", "web/app.TSX");
		assertThat(result.sourceFiles()).extracting(SourceFile::language)
				.containsExactly(Language.JAVA, Language.PYTHON, Language.TYPESCRIPT);
		assertThat(result.languageFiles()).containsEntry(Language.JAVA, 1).containsEntry(Language.PYTHON, 1);
		assertThat(result.totalFiles()).isEqualTo(5);
		// every file is listed, source or not, for technology detection
		assertThat(result.allPaths()).containsExactly("Makefile", "README.md", "src/Main.java", "tools/run.py", "web/app.TSX");
	}

	@Test
	void recognisesManyLanguagesAndInfrastructureFilesByName() throws IOException {
		write("app.php", "<?php"); write("a.rb", "x"); write("C.sol", "x"); write("c.vue", "x"); write("n.ipynb", "{}");
		write("deploy/Dockerfile", "FROM x"); write("infra/main.tf", "x"); write("q.sql", "x"); write("App.swift", "x");
		write("lib/bundle.min.js", "x"); write("types.d.ts", "x"); write(".github/workflows/ci.yml", "x");

		DiscoveredFiles result = discovery().discover(root);

		assertThat(result.languageFiles()).containsEntry(Language.PHP, 1).containsEntry(Language.RUBY, 1)
				.containsEntry(Language.SOLIDITY, 1).containsEntry(Language.VUE, 1).containsEntry(Language.JUPYTER, 1)
				.containsEntry(Language.DOCKERFILE, 1).containsEntry(Language.HCL, 1).containsEntry(Language.SQL, 1)
				.containsEntry(Language.SWIFT, 1);
		// minified bundles and type stubs are not source
		assertThat(result.sourceFiles()).extracting(SourceFile::relativePath).doesNotContain("lib/bundle.min.js", "types.d.ts");
		// the .github directory is kept: CI workflows live there
		assertThat(result.allPaths()).contains(".github/workflows/ci.yml");
	}

	@Test
	void skipsVendoredCodeAndBuildOutput() throws IOException {
		write("src/Keep.java", "class Keep {}");
		for (String ignored : new String[] {".git", "node_modules", "target", "build", "dist", "vendor", "out"}) {
			write(ignored + "/Skipped.java", "class Skipped {}");
			write("module/" + ignored + "/Nested.java", "class Nested {}");
		}

		DiscoveredFiles result = discovery().discover(root);

		assertThat(result.sourceFiles()).extracting(SourceFile::relativePath).containsExactly("src/Keep.java");
	}

	@Test
	void skipsSourceFilesAboveTheSizeLimitButStillCountsThem() throws IOException {
		write("Small.java", "class Small {}");
		write("Generated.java", "x".repeat(500));

		DiscoveredFiles result = discovery(1000, 10_000_000, 100).discover(root);

		assertThat(result.sourceFiles()).extracting(SourceFile::relativePath).containsExactly("Small.java");
		assertThat(result.totalFiles()).isEqualTo(2);
	}

	@Test
	void ignoresSymbolicLinks() throws IOException {
		write("Real.java", "class Real {}");
		Path outside = Files.createTempFile("outside", ".java");
		try {
			Files.createSymbolicLink(root.resolve("Link.java"), outside);

			DiscoveredFiles result = discovery().discover(root);

			assertThat(result.sourceFiles()).extracting(SourceFile::relativePath).containsExactly("Real.java");
		}
		finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	void failsWhenTheRepositoryHasTooManyFiles() throws IOException {
		for (int i = 0; i < 5; i++) {
			write("f" + i + ".txt", "x");
		}

		assertThatThrownBy(() -> discovery(4, 10_000_000, 1_000_000).discover(root))
				.isInstanceOf(AnalysisFailureException.class)
				.hasMessageContaining("more than 4 files");
	}

	@Test
	void failsWhenTheRepositoryIsTooLarge() throws IOException {
		write("a.bin", "x".repeat(2 * 1024 * 1024));

		assertThatThrownBy(() -> discovery(1000, 1024 * 1024, 1_000_000).discover(root))
				.isInstanceOf(AnalysisFailureException.class)
				.hasMessageContaining("larger than 1 MB");
	}

	@Test
	void ignoredDirectoriesDoNotCountTowardsTheLimits() throws IOException {
		for (int i = 0; i < 20; i++) {
			write("node_modules/pkg/f" + i + ".js", "x");
		}
		write("Main.java", "class Main {}");

		DiscoveredFiles result = discovery(2, 10_000_000, 1_000_000).discover(root);

		assertThat(result.totalFiles()).isEqualTo(1);
	}
}
