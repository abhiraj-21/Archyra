package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ingestion.GitCloner;

/**
 * The whole pipeline against real PostgreSQL and Neo4j (Testcontainers) and the real Tree-sitter parser.
 * Only the GitHub clone is replaced: it copies a fixture repository instead of using the network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "00000000-0000-4000-8000-0000000000a1")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AnalysisPipelineIntegrationTest {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");
	private static final String URL = "https://github.com/example/sample-shop";

	private static final String CONTROLLER = "class:com.example.shop.web.OrderController";
	private static final String ORDER_SERVICE = "interface:com.example.shop.service.OrderService";
	private static final String DEFAULT_SERVICE = "class:com.example.shop.service.DefaultOrderService";
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

	// ---- happy path ----------------------------------------------------------------------------

	@Test
	void analysesARepositoryEndToEndAndBuildsTheGraph() {
		UUID id = startAnalysis();

		Analysis analysis = awaitFinished(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getProgress()).isEqualTo(100);
		assertThat(analysis.getFailureReason()).isNull();
		assertThat(analysis.getRepositoryUrl()).isEqualTo(URL);

		String analysisId = id.toString();
		assertThat(countNodes(analysisId, "Repository")).isEqualTo(1);
		assertThat(countNodes(analysisId, "Module")).isEqualTo(6);   // shop, model, repository, service, web, and the root of script.py
		assertThat(countNodes(analysisId, "File")).isEqualTo(10);    // 9 Java files and script.py
		assertThat(countNodes(analysisId, "Interface")).isEqualTo(2 + 0); // OrderService, OrderRepository
		assertThat(nodeProperty(analysisId, "Class", ORDER, "kind")).isEqualTo("CLASS");
		assertThat(nodeProperty(analysisId, "Class", "class:com.example.shop.model.OrderRequest", "kind")).isEqualTo("RECORD");
		assertThat(nodeProperty(analysisId, "Class", "class:com.example.shop.model.Order.Status", "kind")).isEqualTo("ENUM");
	}

	@Test
	void everyNodeBelongsToTheAnalysis() {
		UUID id = startAnalysis();
		awaitFinished(id);

		long withoutAnalysisId = neo4jClient.query("MATCH (n) WHERE n.analysisId IS NULL RETURN count(n) AS c")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(-1L);
		assertThat(withoutAnalysisId).isZero();
	}

	@Test
	void containmentFormsATreeFromRepositoryToMethods() {
		String analysisId = completedAnalysis();

		assertThat(edge(analysisId, "Repository", "repository:sample-shop", "CONTAINS", "Module", "module:com.example.shop.web")).isTrue();
		assertThat(edge(analysisId, "Module", "module:com.example.shop.web", "CONTAINS", "File",
				"file:src/main/java/com/example/shop/web/OrderController.java")).isTrue();
		assertThat(edge(analysisId, "File", "file:src/main/java/com/example/shop/web/OrderController.java", "CONTAINS",
				"Class", CONTROLLER)).isTrue();
		assertThat(edge(analysisId, "Class", CONTROLLER, "CONTAINS", "Method",
				"method:com.example.shop.web.OrderController#create(OrderRequest)")).isTrue();
		assertThat(edge(analysisId, "Class", ORDER, "CONTAINS", "Class", "class:com.example.shop.model.Order.Status")).isTrue();
	}

	@Test
	void inheritanceRelationshipsAreResolved() {
		String analysisId = completedAnalysis();

		assertThat(edge(analysisId, "Class", ORDER, "EXTENDS", "Class", BASE_ENTITY)).isTrue();
		assertThat(edge(analysisId, "Class", DEFAULT_SERVICE, "IMPLEMENTS", "Interface", ORDER_SERVICE)).isTrue();
		assertThat(edge(analysisId, "Class", "class:com.example.shop.repository.InMemoryOrderRepository", "IMPLEMENTS",
				"Interface", "interface:com.example.shop.repository.OrderRepository")).isTrue();
	}

	@Test
	void classDependenciesFollowTheLayers() {
		String analysisId = completedAnalysis();

		assertThat(edge(analysisId, "Class", CONTROLLER, "DEPENDS_ON", "Interface", ORDER_SERVICE)).isTrue();
		assertThat(edge(analysisId, "Class", CONTROLLER, "DEPENDS_ON", "Class", ORDER)).isTrue();
		assertThat(edge(analysisId, "Class", DEFAULT_SERVICE, "DEPENDS_ON", "Interface",
				"interface:com.example.shop.repository.OrderRepository")).isTrue();
		assertThat(edge(analysisId, "Class", DEFAULT_SERVICE, "DEPENDS_ON", "Class", ORDER)).isTrue();
		// no layer skipping and nothing points back up
		assertThat(edge(analysisId, "Class", CONTROLLER, "DEPENDS_ON", "Interface",
				"interface:com.example.shop.repository.OrderRepository")).isFalse();
		assertThat(edge(analysisId, "Class", ORDER, "DEPENDS_ON", "Class", CONTROLLER)).isFalse();
	}

	@Test
	void methodCallsAreResolvedThroughFieldsLocalsThisAndInheritance() {
		String analysisId = completedAnalysis();
		String create = "method:com.example.shop.web.OrderController#create(OrderRequest)";
		String createDefault = "method:com.example.shop.web.OrderController#createDefault()";
		String place = "method:com.example.shop.service.OrderService#place(OrderRequest)";

		assertThat(edge(analysisId, "Method", create, "CALLS", "Method", place)).isTrue();          // field
		assertThat(edge(analysisId, "Method", createDefault, "CALLS", "Method", place)).isTrue();   // this.field
		assertThat(edge(analysisId, "Method", "method:com.example.shop.web.OrderController#log(Order)", "CALLS",
				"Method", "method:com.example.shop.web.OrderController#print(Order)")).isTrue();    // unqualified
		assertThat(edge(analysisId, "Method", "method:com.example.shop.web.OrderController#print(Order)", "CALLS",
				"Method", "method:com.example.shop.model.BaseEntity#getId()")).isTrue();            // inherited
		assertThat(edge(analysisId, "Method", "method:com.example.shop.service.DefaultOrderService#place(OrderRequest)",
				"CALLS", "Method", "method:com.example.shop.model.Order#addItem(String)")).isTrue(); // local
		assertThat(edge(analysisId, "Method", "method:com.example.shop.service.DefaultOrderService#place(OrderRequest)",
				"CALLS", "Method", "method:com.example.shop.repository.OrderRepository#save(Order)")).isTrue();
	}

	@Test
	void importsLinkFilesAndExternalTypesAreMarkedExternal() {
		String analysisId = completedAnalysis();
		String controllerFile = "file:src/main/java/com/example/shop/web/OrderController.java";

		assertThat(edge(analysisId, "File", controllerFile, "IMPORTS", "File",
				"file:src/main/java/com/example/shop/service/OrderService.java")).isTrue();
		assertThat(edge(analysisId, "File", "file:src/main/java/com/example/shop/model/Order.java", "IMPORTS", "Class",
				"external:class:java.util.List")).isTrue();
		assertThat(nodeProperty(analysisId, "Class", "external:class:java.util.List", "external")).isEqualTo(true);
		assertThat(nodeProperty(analysisId, "Class", ORDER, "external")).isEqualTo(false);
		assertThat(edge(analysisId, "Class", "class:com.example.shop.repository.InMemoryOrderRepository", "REFERENCES",
				"Class", "external:class:java.util.Map")).isTrue();
		// a call on an external type becomes an external method node
		assertThat(edge(analysisId, "Method", "method:com.example.shop.repository.InMemoryOrderRepository#save(Order)",
				"CALLS", "Method", "external:method:java.util.Map#put")).isTrue();
	}

	@Test
	void aFileWithSyntaxErrorsDoesNotFailTheAnalysis() {
		String analysisId = completedAnalysis();

		// Broken.java is malformed, but whatever Tree-sitter could recognise is still in the graph.
		assertThat(countNodes(analysisId, "File")).isEqualTo(10);
		assertThat(nodeProperty(analysisId, "Class", "class:com.example.shop.Survivor", "name")).isEqualTo("Survivor");
	}

	@Test
	void graphsOfDifferentAnalysesNeverConnect() {
		UUID first = startAnalysis();
		UUID second = UUID.fromString(analysisService.createAnalysis(new CreateAnalysisRequest(URL + "-two")).analysisId()); // the same URL would join the running one
		awaitFinished(first);
		awaitFinished(second);

		assertThat(countNodes(first.toString(), "Class")).isEqualTo(countNodes(second.toString(), "Class")).isPositive();
		long crossing = neo4jClient.query("MATCH (a)-[r]->(b) WHERE a.analysisId <> b.analysisId RETURN count(r) AS c")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(-1L);
		assertThat(crossing).isZero();
	}

	@Test
	void postReturnsImmediatelyAndTheAnalysisFinishesInTheBackground() throws Exception {
		String response = mockMvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON)
						.content("{\"repositoryUrl\":\"" + URL + "\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(response.replaceAll(".*\"analysisId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

		assertThat(awaitFinished(id).getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
	}

	// ---- failures ------------------------------------------------------------------------------

	@Test
	void aFailedCloneEndsAsFailedWithTheReasonAndNoGraph() {
		doThrow(new AnalysisFailureException("The repository could not be cloned. Check that it exists and is public."))
				.when(gitCloner).clone(anyString(), any(Path.class));
		UUID id = startAnalysis();

		Analysis analysis = awaitFinished(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).isEqualTo("The repository could not be cloned. Check that it exists and is public.");
		assertThat(analysis.getProgress()).isEqualTo(5);
		assertThat(countAllNodes(id.toString())).isZero();
	}

	@Test
	void aRepositoryWithoutSupportedSourcesEndsAsFailedWithAClearReason() {
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1);
			Files.createDirectories(target);
			Files.writeString(target.resolve("README.md"), "# nothing to parse");
			Files.writeString(target.resolve("App.swift"), "print(\"swift has no extractor yet\")");
			Files.writeString(target.resolve("schema.sql"), "CREATE TABLE t (id int);");
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
		UUID id = startAnalysis();

		Analysis analysis = awaitFinished(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).contains("No source files could be analysed").contains("Java");
		assertThat(analysis.getProgress()).isEqualTo(25);
		assertThat(countAllNodes(id.toString())).isZero();
	}

	// ---- helpers -------------------------------------------------------------------------------

	private UUID startAnalysis() {
		return UUID.fromString(analysisService.createAnalysis(new CreateAnalysisRequest(URL)).analysisId());
	}

	private String completedAnalysis() {
		UUID id = startAnalysis();
		assertThat(awaitFinished(id).getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		return id.toString();
	}

	private Analysis awaitFinished(UUID id) {
		await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200))
				.until(() -> analysisRepository.findById(id).map(Analysis::isFinished).orElse(false));
		return analysisRepository.findById(id).orElseThrow();
	}

	private long countNodes(String analysisId, String label) {
		return neo4jClient.query("MATCH (n:" + label + " {analysisId: $id}) WHERE n.external = false RETURN count(n) AS c")
				.bind(analysisId).to("id")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(-1L);
	}

	private long countAllNodes(String analysisId) {
		return neo4jClient.query("MATCH (n {analysisId: $id}) RETURN count(n) AS c")
				.bind(analysisId).to("id")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(-1L);
	}

	private Object nodeProperty(String analysisId, String label, String nodeId, String property) {
		return neo4jClient.query("MATCH (n:" + label + " {analysisId: $id, nodeId: $nodeId}) RETURN n." + property + " AS p")
				.bind(analysisId).to("id").bind(nodeId).to("nodeId")
				.fetchAs(Object.class).mappedBy((ts, r) -> r.get("p").asObject()).one().orElse(null);
	}

	private boolean edge(String analysisId, String sourceLabel, String sourceId, String type, String targetLabel, String targetId) {
		return neo4jClient.query("MATCH (a:" + sourceLabel + " {analysisId: $id, nodeId: $source})-[r:" + type + "]->(b:"
						+ targetLabel + " {analysisId: $id, nodeId: $target}) RETURN count(r) AS c")
				.bind(analysisId).to("id").bind(sourceId).to("source").bind(targetId).to("target")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(0L) > 0;
	}
}
