package com.abhiraj.archyra.ai.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.NodeIR;
import com.abhiraj.archyra.ir.NodeType;

/** Chunk capture over real parses of real fixtures: lines, text, exclusions, truncation and the cap. */
class ChunkExtractorTest {

	private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

	private static ChunkExtractor extractor(int maxChars, int maxPerAnalysis) {
		return new ChunkExtractor(new AiProperties(null, null, null, null, null,
				new AiProperties.Chunk(maxChars, maxPerAnalysis), null, null, null), new ProjectDocs());
	}

	private static ChunkSet capture(RealGraph graph) {
		return extractor(6000, 20000).extract(graph.graph, graph.root);
	}

	/** The chunks of code: the README and manifest chunks of the repository node come after them and are tested apart. */
	private static List<ChunkData> codeOf(ChunkSet set) {
		return set.chunks().stream().filter(chunk -> !chunk.kind().isProjectDoc()).toList();
	}

	private static ChunkData chunk(ChunkSet set, String nodeId) {
		return set.chunks().stream().filter(chunk -> chunk.nodeId().equals(nodeId)).findFirst()
				.orElseThrow(() -> new AssertionError("No chunk for " + nodeId + " in " + set.chunks().stream().map(ChunkData::nodeId).toList()));
	}

	private static List<String> lines(Path root, String file) throws IOException {
		return Files.readAllLines(root.resolve(file));
	}

	// ---- the sample repository -----------------------------------------------------------------

	@Test
	void aTypeHeaderRunsFromTheSignatureToTheLineBeforeItsFirstMember() throws IOException {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("sample-repo"));
		ChunkData header = chunk(capture(graph), "class:com.example.shop.service.DefaultOrderService");

		String file = "src/main/java/com/example/shop/service/DefaultOrderService.java";
		assertThat(header.kind()).isEqualTo(ChunkKind.TYPE_HEADER);
		assertThat(header.file()).isEqualTo(file);
		assertThat(header.content()).startsWith("public class DefaultOrderService implements OrderService {")
				.contains("private final OrderRepository repository;")
				.doesNotContain("return repository.save").doesNotContain("public DefaultOrderService(");
		List<String> source = lines(graph.root, file);
		assertThat(source.get(header.startLine() - 1)).isEqualTo("public class DefaultOrderService implements OrderService {");
		assertThat(source.get(header.endLine() - 1)).contains("private final OrderRepository repository;");
	}

	@Test
	void aMethodChunkIsExactlyItsLinesIncludingItsAnnotation() throws IOException {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("sample-repo"));
		ChunkData place = chunk(capture(graph), "method:com.example.shop.service.DefaultOrderService#place(OrderRequest)");

		NodeIR node = graph.node("method:com.example.shop.service.DefaultOrderService#place(OrderRequest)");
		assertThat(place.kind()).isEqualTo(ChunkKind.METHOD);
		assertThat(place.startLine()).isEqualTo(node.line());
		assertThat(place.endLine()).isEqualTo(node.endLine());
		assertThat(place.content()).startsWith("@Override").contains("public Order place(OrderRequest request) {")
				.contains("return repository.save(order);").endsWith("}");
		assertThat(place.content().lines().count()).isEqualTo(place.endLine() - place.startLine() + 1);
	}

	@Test
	void aTypeWithoutMembersGetsItsWholeDeclaration() {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("sample-repo"));
		ChunkData status = chunk(capture(graph), "class:com.example.shop.model.Order.Status");

		assertThat(status.kind()).isEqualTo(ChunkKind.TYPE_HEADER);
		assertThat(status.content()).contains("enum Status").contains("}");
		assertThat(status.endLine()).isGreaterThanOrEqualTo(status.startLine());
	}

	@Test
	void everyChunkIsOfAWorkingNonExternalNodeWithTextAndAValidRange() throws IOException {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("sample-repo"));
		ChunkSet set = capture(graph);

		assertThat(codeOf(set)).isNotEmpty();
		assertThat(set.capped()).isFalse();
		for (ChunkData chunk : codeOf(set)) {
			NodeIR node = graph.node(chunk.nodeId());
			assertThat(node.external()).isFalse();
			assertThat(node.type()).isIn(NodeType.CLASS, NodeType.INTERFACE, NodeType.METHOD, NodeType.FUNCTION);
			assertThat(chunk.content()).isNotBlank();
			assertThat(chunk.startLine()).isBetween(1, chunk.endLine());
			assertThat(chunk.endLine()).isLessThanOrEqualTo(lines(graph.root, chunk.file()).size());
		}
		assertThat(codeOf(set)).extracting(ChunkData::nodeId).doesNotHaveDuplicates();
	}

	@Test
	void theResultIsDeterministicAndInFileOrder() {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("sample-repo"));

		ChunkSet first = capture(graph);
		ChunkSet second = capture(graph);

		assertThat(first).isEqualTo(second);
		List<String> files = codeOf(first).stream().map(ChunkData::file).distinct().toList();
		assertThat(files).isSorted();
		for (String file : files) {
			assertThat(codeOf(first).stream().filter(chunk -> chunk.file().equals(file)).map(ChunkData::startLine).toList())
					.isSorted();
		}
	}

	// ---- test code is excluded -----------------------------------------------------------------

	@Test
	void testCodeIsNeverChunkedButAMainPackageNamedTestIs() {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("tests-and-main"));
		ChunkSet set = capture(graph);

		assertThat(set.chunks()).extracting(ChunkData::file)
				.contains("src/main/java/com/acme/app/GreetingService.java", "src/main/java/com/acme/app/test/Support.java")
				.noneMatch(file -> file.contains("src/test/"));
		assertThat(set.chunks()).extracting(ChunkData::nodeId).noneMatch(id -> id.contains("GreetingServiceTest"))
				.noneMatch(id -> id.contains("Samples"));
	}

	@Test
	void pythonTestFilesAreExcludedAndFunctionsGetFunctionChunks() {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("tests-and-main"));
		ChunkSet set = capture(graph);

		assertThat(set.chunks()).extracting(ChunkData::file).contains("tools/cleaner.py").doesNotContain("tests/test_cleaner.py");
		assertThat(set.chunks()).filteredOn(chunk -> chunk.file().equals("tools/cleaner.py"))
				.extracting(ChunkData::kind).containsOnly(ChunkKind.FUNCTION);
	}

	// ---- languages whose line numbers need care ------------------------------------------------

	@Test
	void goReceiverMethodsGetTheirOwnChunksAndTheExtensionStubGetsNoHeader() {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("polyglot/go"));
		ChunkSet set = capture(graph);

		ChunkData start = set.chunks().stream().filter(chunk -> chunk.file().equals("internal/store/server.go"))
				.filter(chunk -> chunk.content().startsWith("func (s *Server) Start()")).findFirst().orElseThrow();
		assertThat(start.kind()).isEqualTo(ChunkKind.METHOD);
		assertThat(start.content()).contains("helper(1, 2)").endsWith("}");
		// the stub type made of receiver methods carries no text of its own: its methods do
		assertThat(set.chunks()).filteredOn(chunk -> chunk.file().equals("internal/store/server.go"))
				.extracting(ChunkData::kind).doesNotContain(ChunkKind.TYPE_HEADER);
	}

	@Test
	void aNotebookIsChunkedFromItsJoinedCodeNotItsJson() {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("polyglot/frontend"));
		ChunkSet set = capture(graph);

		List<ChunkData> notebook = set.chunks().stream().filter(chunk -> chunk.file().equals("notebooks/analysis.ipynb")).toList();
		assertThat(notebook).isNotEmpty();
		assertThat(notebook).extracting(ChunkData::content).anyMatch(text -> text.startsWith("def clean(df):")
				&& text.contains("return df.dropna()"));
		assertThat(notebook).extracting(ChunkData::content).noneMatch(text -> text.contains("cell_type") || text.contains("\"source\""));
	}

	@Test
	void vueScriptDeclarationsAreCutFromTheOriginalFileLines() throws IOException {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("polyglot/frontend"));
		ChunkSet set = capture(graph);

		List<ChunkData> app = set.chunks().stream().filter(chunk -> chunk.file().equals("src/App.vue")).toList();
		assertThat(app).isNotEmpty().allSatisfy(chunk -> assertThat(lines(graph.root, "src/App.vue")
				.get(chunk.startLine() - 1)).as("first line of the chunk").isNotBlank());
	}

	// ---- limits and robustness -----------------------------------------------------------------

	@Test
	void aTextOverTheLimitKeepsItsHeadAndEndsWithAMarker(@TempDir Path root) throws IOException {
		Files.createDirectories(root.resolve("src/main/java/p"));
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 400; i++) {
			body.append("        total += compute").append(i).append("(input);\n");
		}
		Files.writeString(root.resolve("src/main/java/p/Big.java"),
				"package p;\n\npublic class Big {\n    public int run(int input) {\n        int total = 0;\n" + body
						+ "        return total;\n    }\n}\n");
		RealGraph graph = RealGraph.of(root);

		ChunkSet set = extractor(500, 100).extract(graph.graph, graph.root);

		ChunkData run = chunk(set, "method:p.Big#run(int)");
		assertThat(run.content()).startsWith("public int run(int input) {").endsWith("... [truncated]");
		assertThat(run.content().length()).isLessThanOrEqualTo(500 + ChunkExtractor.TRUNCATION_MARKER.length());
		assertThat(run.endLine()).isGreaterThan(400); // the range stays the real one
	}

	@Test
	void aTypeHeaderIsCappedAtEightyLines(@TempDir Path root) throws IOException {
		Files.createDirectories(root.resolve("src/main/java/p"));
		StringBuilder fields = new StringBuilder();
		for (int i = 0; i < 200; i++) {
			fields.append("    private int field").append(i).append(";\n");
		}
		Files.writeString(root.resolve("src/main/java/p/Wide.java"),
				"package p;\n\npublic class Wide {\n" + fields + "    public void go() {}\n}\n");
		RealGraph graph = RealGraph.of(root);

		ChunkData header = chunk(capture(graph), "class:p.Wide");

		assertThat(header.endLine() - header.startLine() + 1).isEqualTo(ChunkExtractor.MAX_HEADER_LINES);
		assertThat(header.content()).doesNotContain("field150");
	}

	@Test
	void beyondTheLimitTheFirstChunksInFileOrderAreKeptAndTheResultSaysSo() {
		RealGraph graph = RealGraph.of(FIXTURES.resolve("sample-repo"));
		ChunkSet all = capture(graph);
		assertThat(codeOf(all).size()).isGreaterThan(5);

		ChunkSet capped = extractor(6000, 5).extract(graph.graph, graph.root);

		assertThat(capped.capped()).isTrue();
		assertThat(codeOf(capped)).hasSize(5).isEqualTo(codeOf(all).subList(0, 5));
		// the README comes after the cap: two chunks of the repository's own words cannot be crowded out by code
		assertThat(capped.chunks()).filteredOn(chunk -> chunk.kind().isProjectDoc()).hasSize(1);
		assertThat(extractor(6000, codeOf(all).size()).extract(graph.graph, graph.root).capped()).isFalse(); // exactly full is not capped
	}

	@Test
	void aRepositoryOfOnlyTestCodeYieldsNoChunks(@TempDir Path root) throws IOException {
		Files.createDirectories(root.resolve("tests"));
		Files.writeString(root.resolve("tests/test_it.py"), "def test_it():\n    assert True\n");
		RealGraph graph = RealGraph.of(root);
		assertThat(graph.graph.nodes()).anyMatch(node -> node.type() == NodeType.FUNCTION); // it was analysed

		ChunkSet set = capture(graph);

		assertThat(set.chunks()).isEmpty();
		assertThat(set.capped()).isFalse();
	}

	// ---- the README and the manifests ----------------------------------------------------------

	private static void javaRepository(Path root) throws IOException {
		Files.createDirectories(root.resolve("src/main/java/p"));
		Files.writeString(root.resolve("src/main/java/p/Ok.java"), "package p;\n\npublic class Ok {\n    public void go() {}\n}\n");
	}

	@Test
	void theReadmeAndTheManifestBecomeChunksOfTheRepositoryNodeAfterTheCode(@TempDir Path root) throws IOException {
		javaRepository(root);
		Files.writeString(root.resolve("README.md"), "# Shop\n\n[![Build](https://ci/badge.svg)](https://ci)\n\nA small shop that takes orders.\n");
		Files.writeString(root.resolve("pom.xml"), "<project><name>Shop</name><description>Takes orders</description></project>");
		RealGraph graph = RealGraph.of(root);

		ChunkSet set = capture(graph);

		String repository = graph.graph.nodes().stream().filter(node -> node.type() == NodeType.REPOSITORY).findFirst().orElseThrow().id();
		List<ChunkData> docs = set.chunks().stream().filter(chunk -> chunk.kind().isProjectDoc()).toList();
		assertThat(docs).extracting(ChunkData::kind).containsExactly(ChunkKind.README, ChunkKind.MANIFEST);
		assertThat(docs).extracting(ChunkData::nodeId).containsOnly(repository);
		assertThat(docs.getFirst().file()).isEqualTo("README.md");
		assertThat(docs.getFirst().content()).contains("A small shop that takes orders.").doesNotContain("badge").doesNotContain("[!");
		assertThat(docs.getFirst().startLine()).isEqualTo(1);
		assertThat(docs.get(1).file()).isEqualTo("pom.xml");
		assertThat(docs.get(1).content()).contains("name: Shop").contains("description: Takes orders");
		assertThat(set.chunks().subList(set.chunks().size() - 2, set.chunks().size())).isEqualTo(docs); // after the code
	}

	@Test
	void aRepositoryWithoutAReadmeOrManifestIsFine(@TempDir Path root) throws IOException {
		javaRepository(root);

		ChunkSet set = capture(RealGraph.of(root));

		assertThat(set.chunks()).isNotEmpty().allMatch(chunk -> !chunk.kind().isProjectDoc());
	}

	@Test
	void aReadmeAloneIsNotIndexedSoAnAnalysisOfOnlyTestsStaysEmpty(@TempDir Path root) throws IOException {
		Files.createDirectories(root.resolve("tests"));
		Files.writeString(root.resolve("tests/test_it.py"), "def test_it():\n    assert True\n");
		Files.writeString(root.resolve("README.md"), "# Only tests\n\nNothing but tests here.\n");
		Files.writeString(root.resolve("package.json"), "{\"name\": \"only-tests\", \"description\": \"tests\"}");

		ChunkSet set = capture(RealGraph.of(root));

		assertThat(set.chunks()).isEmpty();
	}

	@Test
	void anUnreadableReadmeOrManifestSkipsOnlyThatChunk(@TempDir Path root) throws IOException {
		javaRepository(root);
		Files.createDirectories(root.resolve("README.md")); // a directory with the name of a README
		Files.writeString(root.resolve("package.json"), "{ this is not json");
		Files.writeString(root.resolve("go.mod"), "module example.com/shop\n");

		ChunkSet set = capture(RealGraph.of(root));

		assertThat(codeOf(set)).isNotEmpty();
		assertThat(set.chunks()).filteredOn(chunk -> chunk.kind().isProjectDoc()).singleElement().satisfies(chunk -> {
			assertThat(chunk.kind()).isEqualTo(ChunkKind.MANIFEST);
			assertThat(chunk.content()).contains("module: example.com/shop").doesNotContain("not json");
		});
	}

	@Test
	void aFileThatCannotBeReadOrEscapesTheCloneIsSkippedWithoutFailing(@TempDir Path root) throws IOException {
		Files.createDirectories(root.resolve("src/main/java/p"));
		Files.writeString(root.resolve("src/main/java/p/Ok.java"), "package p;\n\npublic class Ok {\n    public void go() {}\n}\n");
		CodeGraphIR real = RealGraph.of(root).graph;
		java.util.ArrayList<NodeIR> nodes = new java.util.ArrayList<>(real.nodes());
		nodes.add(new NodeIR("method:p.Gone#x()", NodeType.METHOD, "x", "p.Gone#x()", "src/main/java/p/Gone.java", 1, 3, null, false));
		nodes.add(new NodeIR("method:p.Evil#x()", NodeType.METHOD, "x", "p.Evil#x()", "../outside.txt", 1, 1, null, false));
		CodeGraphIR graph = new CodeGraphIR("repo", nodes, real.edges());

		ChunkSet set = extractor(6000, 100).extract(graph, root);

		assertThat(set.chunks()).extracting(ChunkData::nodeId).contains("method:p.Ok#go()")
				.doesNotContain("method:p.Gone#x()", "method:p.Evil#x()");
	}

	@Test
	void nulCharactersAreRemovedBecausePostgresCannotStoreThem(@TempDir Path root) throws IOException {
		Files.createDirectories(root.resolve("src/main/java/p"));
		Files.writeString(root.resolve("src/main/java/p/Nul.java"),
				"package p;\n\npublic class Nul {\n    public String s() { return \"a\u0000b\"; }\n}\n");

		ChunkSet set = capture(RealGraph.of(root));

		assertThat(set.chunks()).isNotEmpty().allSatisfy(chunk -> assertThat(chunk.content()).doesNotContain("\u0000"));
	}
}
