package com.abhiraj.archyra.parsing.lang;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.abhiraj.archyra.ingestion.DiscoveredFiles;
import com.abhiraj.archyra.ingestion.FileDiscovery;
import com.abhiraj.archyra.ingestion.IngestionProperties;
import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.EdgeIR;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.NodeIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.RepositoryIR;
import com.abhiraj.archyra.parsing.LanguageExtractor;
import com.abhiraj.archyra.parsing.NativeLibraries;
import com.abhiraj.archyra.parsing.ParseResult;
import com.abhiraj.archyra.parsing.ParsingService;
import com.abhiraj.archyra.parsing.ReferenceResolver;

/**
 * Test support: runs file discovery, the real parser and reference resolution over a fixture directory,
 * exactly as the pipeline does, without Spring.
 */
final class FixtureAnalysis {

	private static final Path FIXTURES = Path.of("src/test/resources/fixtures/polyglot");

	final DiscoveredFiles discovered;
	final ParseResult parsed;
	final CodeGraphIR graph;

	private FixtureAnalysis(DiscoveredFiles discovered, ParseResult parsed, CodeGraphIR graph) {
		this.discovered = discovered;
		this.parsed = parsed;
		this.graph = graph;
	}

	static List<LanguageExtractor> extractors() {
		return Languages.extractors();
	}

	static FixtureAnalysis of(String fixture) {
		NativeLibraries.configure(Path.of("native"));
		Path root = FIXTURES.resolve(fixture);
		DiscoveredFiles discovered = new FileDiscovery(
				new IngestionProperties(10_000, 200_000_000, 1_000_000, Duration.ofMinutes(1))).discover(root);
		ParseResult parsed = new ParsingService(extractors())
				.parse(fixture, "https://github.com/example/" + fixture, discovered.sourceFiles());
		CodeGraphIR graph = new ReferenceResolver(Languages.moduleResolvers()).resolve(parsed.repository());
		return new FixtureAnalysis(discovered, parsed, graph);
	}

	/** The IR of a single fixture file. */
	static FileIR file(String fixture, String relativePath) {
		return of(fixture).parsed.repository().files().stream()
				.filter(file -> file.path().equals(relativePath)).findFirst()
				.orElseThrow(() -> new AssertionError("Not parsed: " + relativePath));
	}

	boolean edge(String source, RelationshipType type, String target) {
		return graph.edges().contains(new EdgeIR(source, target, type));
	}

	NodeIR node(String id) {
		return graph.nodes().stream().filter(node -> node.id().equals(id)).findFirst().orElse(null);
	}

	long count(RelationshipType type) {
		return graph.edges().stream().filter(edge -> edge.type() == type).count();
	}

	SourceFile source(String path) {
		return discovered.sourceFiles().stream().filter(file -> file.relativePath().equals(path)).findFirst().orElseThrow();
	}

	RepositoryIR repository() {
		return parsed.repository();
	}
}
