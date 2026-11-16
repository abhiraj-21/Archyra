package com.abhiraj.archyra.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.analysis.AnalysisRepository;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.analysis.AnalysisStatus;
import com.abhiraj.archyra.analysis.CreateAnalysisRequest;
import com.abhiraj.archyra.ingestion.GitCloner;
import com.jayway.jsonpath.JsonPath;

/**
 * {@code GET /api/v1/analyses/{id}/graph} against real PostgreSQL and Neo4j, after the real pipeline analysed
 * a fixture repository. Only the GitHub clone is replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class GraphEndpointIntegrationTest {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");
	private static final String CONTROLLER = "class:com.example.shop.web.OrderController";
	private static final String ORDER_SERVICE = "interface:com.example.shop.service.OrderService";
	private static final String ORDER = "class:com.example.shop.model.Order";
	private static final String BASE_ENTITY = "class:com.example.shop.model.BaseEntity";

	@Autowired
	AnalysisService analysisService;
	@Autowired
	AnalysisRepository analysisRepository;
	@Autowired
	Neo4jClient neo4jClient;
	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	GitCloner gitCloner;

	@BeforeEach
	void cloneTheSampleRepository() {
		doAnswer(invocation -> {
			FileSystemUtils.copyRecursively(SAMPLE_REPO, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	@Test
	void withoutFiltersReturnsTheWholeGraphOfTheAnalysisAndNoExternalNodes() throws Exception {
		UUID id = completedAnalysis();

		Graph graph = fetch(id, "");

		assertThat(graph.nodes()).hasSize((int) count(id, "MATCH (n {analysisId: $id}) WHERE NOT n.external RETURN count(n) AS c"));
		assertThat(graph.edges()).hasSize((int) count(id, """
				MATCH (a {analysisId: $id})-[r]->(b {analysisId: $id}) WHERE NOT a.external AND NOT b.external
				RETURN count(r) AS c"""));
		assertThat(graph.nodeTypes()).contains("REPOSITORY", "MODULE", "FILE", "CLASS", "INTERFACE", "METHOD");
		assertThat(graph.nodeIds()).doesNotContain("external:class:java.util.List");
		assertThat(graph.nodeIds()).noneMatch(nodeId -> nodeId.startsWith("external:"));
		assertThat(graph.edgeEndsAreNodes()).isTrue();
		assertThat(graph.node(CONTROLLER)).containsEntry("type", "CLASS").containsEntry("name", "OrderController")
				.containsEntry("file", "src/main/java/com/example/shop/web/OrderController.java");
		assertThat(graph.node("repository:sample-shop")).doesNotContainKey("file");
	}

	@Test
	void nodeTypeKeepsOnlyThatTypeAndTheEdgesAmongThem() throws Exception {
		UUID id = completedAnalysis();

		Graph graph = fetch(id, "?nodeType=CLASS");

		assertThat(graph.nodeTypes()).containsOnly("CLASS");
		assertThat(graph.nodes()).hasSize((int) count(id, "MATCH (n:Class {analysisId: $id}) WHERE NOT n.external RETURN count(n) AS c"));
		assertThat(graph.edgeEndsAreNodes()).isTrue();
		assertThat(graph.hasEdge(ORDER, BASE_ENTITY, "EXTENDS")).isTrue();
		assertThat(graph.hasEdge(CONTROLLER, ORDER, "DEPENDS_ON")).isTrue();
		assertThat(graph.hasEdge(ORDER, "class:com.example.shop.model.Order.Status", "CONTAINS")).isTrue(); // nested class
		assertThat(graph.edgeTypes()).doesNotContain("CALLS", "IMPLEMENTS", "IMPORTS"); // those end in methods, interfaces, files
		assertThat(graph.hasEdge(CONTROLLER, ORDER_SERVICE, "DEPENDS_ON")).isFalse();    // interface is not a CLASS
	}

	@Test
	void relationshipTypeKeepsOnlyThatTypeOfEdge() throws Exception {
		UUID id = completedAnalysis();

		Graph graph = fetch(id, "?relationshipType=DEPENDS_ON");

		assertThat(graph.edges()).isNotEmpty();
		assertThat(graph.edgeTypes()).containsOnly("DEPENDS_ON");
		assertThat(graph.edges()).hasSize((int) count(id, """
				MATCH (a {analysisId: $id})-[r:DEPENDS_ON]->(b {analysisId: $id}) WHERE NOT a.external AND NOT b.external
				RETURN count(r) AS c"""));
		assertThat(graph.hasEdge(CONTROLLER, ORDER_SERVICE, "DEPENDS_ON")).isTrue();
		assertThat(graph.nodeTypes()).contains("FILE", "METHOD", "CLASS");  // nodes are not narrowed by this filter
		assertThat(graph.edgeEndsAreNodes()).isTrue();
	}

	@Test
	void bothFiltersTogether() throws Exception {
		UUID id = completedAnalysis();

		Graph graph = fetch(id, "?nodeType=CLASS&relationshipType=DEPENDS_ON");

		assertThat(graph.nodeTypes()).containsOnly("CLASS");
		assertThat(graph.edgeTypes()).containsOnly("DEPENDS_ON");
		assertThat(graph.hasEdge(CONTROLLER, ORDER, "DEPENDS_ON")).isTrue();
		assertThat(graph.hasEdge(ORDER, BASE_ENTITY, "EXTENDS")).isFalse();              // other relationship type
		assertThat(graph.hasEdge(CONTROLLER, ORDER_SERVICE, "DEPENDS_ON")).isFalse();    // other node type
		assertThat(graph.edgeEndsAreNodes()).isTrue();
	}

	@Test
	void aFilterThatMatchesNothingGivesAnEmptyGraph() throws Exception {
		UUID id = completedAnalysis();

		Graph graph = fetch(id, "?nodeType=FUNCTION&relationshipType=EXTENDS"); // the sample has no free functions in Java

		assertThat(graph.edges()).isEmpty();
	}

	@Test
	void graphsOfDifferentAnalysesStayApart() throws Exception {
		UUID first = completedAnalysis();
		UUID second = completedAnalysis();

		Graph a = fetch(first, "");
		Graph b = fetch(second, "");

		assertThat(a.nodes()).hasSameSizeAs(b.nodes()).isNotEmpty();
		assertThat(a.edges()).hasSameSizeAs(b.edges()).isNotEmpty();
		// each response is exactly one analysis worth of nodes: no duplicated ids from the other one
		assertThat(a.nodeIds()).doesNotHaveDuplicates();
		assertThat(b.nodeIds()).doesNotHaveDuplicates();
	}

	@Test
	void unknownMalformedAndInvalidFilterRequests() throws Exception {
		UUID id = completedAnalysis();

		mockMvc.perform(get("/api/v1/analyses/{id}/graph", UUID.randomUUID())).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/analyses/{id}/graph", "a8f31c")).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/analyses/{id}/graph?nodeType=TABLE", id)).andExpect(status().isBadRequest());
	}

	// ---- helpers -------------------------------------------------------------------------------

	private Graph fetch(UUID id, String query) throws Exception {
		String body = mockMvc.perform(get("/api/v1/analyses/{id}/graph" + query, id))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Graph(JsonPath.read(body, "$.nodes"), JsonPath.read(body, "$.edges"));
	}

	private record Graph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {

		List<String> nodeIds() {
			return nodes.stream().map(node -> (String) node.get("id")).toList();
		}

		Set<String> nodeTypes() {
			return Set.copyOf(nodes.stream().map(node -> (String) node.get("type")).toList());
		}

		Set<String> edgeTypes() {
			return Set.copyOf(edges.stream().map(edge -> (String) edge.get("type")).toList());
		}

		Map<String, Object> node(String id) {
			return nodes.stream().filter(node -> id.equals(node.get("id"))).findFirst().orElseThrow();
		}

		boolean hasEdge(String source, String target, String type) {
			return edges.stream().anyMatch(edge ->
					source.equals(edge.get("source")) && target.equals(edge.get("target")) && type.equals(edge.get("type")));
		}

		boolean edgeEndsAreNodes() {
			Set<String> ids = Set.copyOf(nodeIds());
			return edges.stream().allMatch(edge -> ids.contains(edge.get("source")) && ids.contains(edge.get("target")));
		}
	}

	private UUID completedAnalysis() {
		UUID id = UUID.fromString(analysisService
				.createAnalysis(new CreateAnalysisRequest("https://github.com/example/sample-shop")).analysisId());
		await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200))
				.until(() -> analysisRepository.findById(id).map(Analysis::isFinished).orElse(false));
		assertThat(analysisRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		return id;
	}

	private long count(UUID id, String cypher) {
		return neo4jClient.query(cypher).bind(id.toString()).to("id")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(-1L);
	}
}
