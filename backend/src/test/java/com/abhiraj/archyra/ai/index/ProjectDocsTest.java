package com.abhiraj.archyra.ai.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a repository's own words: README cleaning and the manifests. Real files, no parser. */
class ProjectDocsTest {

	private static final String REPOSITORY = "repository:demo";
	private final ProjectDocs docs = new ProjectDocs();

	// ---- README cleaning -----------------------------------------------------------------------

	@Test
	void badgesImagesHtmlAndCommentsAreDroppedAndBlankRunsCollapse() {
		String raw = """
				<h1 align="center"><img src="logo.png"></h1>

				[![Build Status](https://travis-ci.org/x/y.svg)](https://travis-ci.org/x/y) [![Coverage](https://img.shields.io/x.svg)](https://x)

				![screenshot](docs/shot.png)
				<!-- a comment
				over two lines -->



				# Demo

				A tiny <b>shop</b> that takes orders.
				[build]: https://img.shields.io/badge/build-passing.svg
				See the [docs](https://example.com/docs) for more.
				""";

		String cleaned = ProjectDocs.cleanReadme(raw);

		assertThat(cleaned).isEqualTo("# Demo\n\nA tiny shop that takes orders.\nSee the [docs](https://example.com/docs) for more.");
	}

	@Test
	void restructuredTextImageDirectivesAndTheirOptionsAreDropped() {
		String cleaned = ProjectDocs.cleanReadme("""
				Demo
				====

				.. image:: https://example.com/badge.svg
				   :target: https://example.com
				   :alt: build

				.. |logo| image:: logo.png

				A tiny shop.
				""");

		assertThat(cleaned).isEqualTo("Demo\n====\n\nA tiny shop.");
	}

	@Test
	void aLineThatMixesTextAndAnImageKeepsTheText() {
		assertThat(ProjectDocs.cleanReadme("Archyra ![logo](l.png) builds graphs")).isEqualTo("Archyra  builds graphs");
	}

	@Test
	void aReadmeThatIsOnlyBadgesHasNoChunk(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("README.md"), "[![a](b.svg)](c)\n\n![d](e.png)\n");

		assertThat(docs.readme(root, REPOSITORY, 6000)).isEmpty();
	}

	// ---- README files --------------------------------------------------------------------------

	@Test
	void theReadmeIsFoundWhateverItsCaseAndExtension(@TempDir Path root) throws IOException {
		for (String name : new String[] { "readme.MD", "Readme.markdown", "README.rst", "ReadMe.TXT", "README" }) {
			Path dir = Files.createTempDirectory(root, "case");
			Files.writeString(dir.resolve(name), "Hello from " + name + "\n");

			Optional<ChunkData> chunk = docs.readme(dir, REPOSITORY, 6000);

			assertThat(chunk).as(name).isPresent();
			assertThat(chunk.get().file()).isEqualTo(name);
			assertThat(chunk.get().kind()).isEqualTo(ChunkKind.README);
			assertThat(chunk.get().nodeId()).isEqualTo(REPOSITORY);
		}
	}

	@Test
	void markdownWinsOverOtherReadmesAndOnlyTheRootIsLookedAt(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("README.txt"), "plain\n");
		Files.writeString(root.resolve("README.md"), "markdown\n");
		Files.createDirectories(root.resolve("docs"));
		Files.writeString(root.resolve("docs/README.md"), "nested\n");

		assertThat(docs.readme(root, REPOSITORY, 6000)).get().satisfies(chunk -> {
			assertThat(chunk.file()).isEqualTo("README.md");
			assertThat(chunk.content()).isEqualTo("markdown");
		});
		Path other = Files.createTempDirectory(root, "only-nested");
		Files.createDirectories(other.resolve("docs"));
		Files.writeString(other.resolve("docs/README.md"), "nested\n");
		assertThat(docs.readme(other, REPOSITORY, 6000)).isEmpty();
	}

	@Test
	void aLongReadmeIsReadOnlyToItsLimitAndCutAtTheChunkSize(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("README.md"), ("A line of text that is long enough to count.\n").repeat(5000)); // about 220 KB

		ChunkData chunk = docs.readme(root, REPOSITORY, 6000).orElseThrow();

		assertThat(chunk.content().length()).isLessThanOrEqualTo(6000 + ChunkExtractor.TRUNCATION_MARKER.length());
		assertThat(chunk.content()).endsWith(ChunkExtractor.TRUNCATION_MARKER);
		assertThat(chunk.endLine()).isEqualTo((int) chunk.content().lines().count());
	}

	@Test
	void aReadmeWithBinaryNoiseAndNulCharactersStaysText(@TempDir Path root) throws IOException {
		Files.write(root.resolve("README.md"), new byte[] { 'H', 'i', 0, ' ', (byte) 0xff, (byte) 0xfe, '\n', 'o', 'k' });

		String content = docs.readme(root, REPOSITORY, 6000).orElseThrow().content();

		assertThat(content).doesNotContain("\u0000").contains("Hi").contains("ok");
	}

	// ---- manifests -----------------------------------------------------------------------------

	@Test
	void pomNameAndDescriptionComeFromTheProjectNotFromItsParentOrDependencies(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("pom.xml"), """
				<?xml version="1.0"?>
				<!-- <name>Commented</name> -->
				<project>
				  <parent><artifactId>spring-boot-starter-parent</artifactId><name>Parent</name></parent>
				  <artifactId>shop</artifactId>
				  <name>The Shop &amp; Co</name>
				  <description>
				    Takes   orders
				  </description>
				  <dependencies><dependency><artifactId>lib</artifactId><name>Lib</name><description>dep</description></dependency></dependencies>
				</project>
				""");

		ChunkData chunk = docs.manifest(root, REPOSITORY).orElseThrow();

		assertThat(chunk.kind()).isEqualTo(ChunkKind.MANIFEST);
		assertThat(chunk.file()).isEqualTo("pom.xml");
		assertThat(chunk.content()).isEqualTo("pom.xml: name: The Shop & Co; description: Takes orders");
	}

	@Test
	void packageJsonGivesNameDescriptionAndKeywords(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("package.json"), """
				{"name": "@acme/shop", "version": "1.0.0", "description": "A shop UI", "keywords": ["shop", "orders", 7, "ui"],
				 "scripts": {"start": "node steal.js"}, "dependencies": {"left-pad": "1"}}
				""");

		assertThat(docs.manifest(root, REPOSITORY).orElseThrow().content())
				.isEqualTo("package.json: name: @acme/shop; description: A shop UI; keywords: shop, orders, ui");
	}

	@Test
	void pyprojectAndCargoUseTheirOwnSections(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("pyproject.toml"), """
				[build-system]
				name = "not-this"
				[project]
				name = "shop"
				description = "Takes \\"orders\\""
				[tool.other]
				description = "nor this"
				""");
		Files.writeString(root.resolve("Cargo.toml"), """
				[package]
				name = 'shop-rs'
				version = "0.1.0"
				description = "Rust shop" # a comment
				[dependencies]
				serde = "1"
				""");

		assertThat(docs.manifest(root, REPOSITORY).orElseThrow().content()).isEqualTo(
				"pyproject.toml: name: shop; description: Takes \"orders\"\nCargo.toml: name: shop-rs; description: Rust shop");
	}

	@Test
	void goModGivesTheModulePath(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("go.mod"), "// comment\nmodule github.com/acme/shop\n\ngo 1.22\nrequire example.com/x v1\n");

		assertThat(docs.manifest(root, REPOSITORY).orElseThrow().content()).isEqualTo("go.mod: module: github.com/acme/shop");
	}

	@Test
	void theManifestChunkIsAtMostFifteenHundredCharactersAndStartsAtLineOne(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("package.json"), "{\"name\": \"x\", \"description\": \"" + "word ".repeat(200) + "\"}");
		Files.writeString(root.resolve("pom.xml"), "<project><name>" + "n".repeat(500) + "</name><description>" + "d".repeat(500) + "</description></project>");

		ChunkData chunk = docs.manifest(root, REPOSITORY).orElseThrow();

		assertThat(chunk.content().length()).isLessThanOrEqualTo(ProjectDocs.MANIFEST_MAX_CHARS);
		assertThat(chunk.startLine()).isEqualTo(1);
		assertThat(chunk.endLine()).isEqualTo((int) chunk.content().lines().count());
	}

	@Test
	void malformedManifestsAreSkippedAndNothingIsExecuted(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("package.json"), "[1, 2, 3]");
		Files.writeString(root.resolve("pom.xml"), "<project><name>unclosed");
		Files.writeString(root.resolve("pyproject.toml"), "\u0000\u0001 garbage ] [ = =");
		Files.write(root.resolve("go.mod"), "module\n".getBytes(StandardCharsets.UTF_8));

		assertThat(docs.manifest(root, REPOSITORY)).isEmpty();
	}

	@Test
	void aSymbolicLinkToAFileOutsideTheCloneIsNotRead(@TempDir Path root, @TempDir Path outside) throws IOException {
		Files.writeString(outside.resolve("secret.md"), "do not read me\n");
		Files.writeString(outside.resolve("secret.json"), "{\"name\": \"stolen\"}");
		Files.createSymbolicLink(root.resolve("README.md"), outside.resolve("secret.md"));
		Files.createSymbolicLink(root.resolve("package.json"), outside.resolve("secret.json"));

		assertThat(docs.readme(root, REPOSITORY, 6000)).isEmpty();
		assertThat(docs.manifest(root, REPOSITORY)).isEmpty();
	}
}
