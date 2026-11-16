package com.abhiraj.archyra.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * {@code GET /api/v1/analyses/{id}/nodes/{nodeId}} over real HTTP (embedded Tomcat), because node ids
 * contain {@code /}, {@code #}, {@code (} and spaces and must survive percent-encoding all the way to the
 * controller. PostgreSQL and Neo4j are real; only the GitHub clone is replaced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class NodeEndpointHttpTest {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");
	private static final String CONTROLLER_FILE = "file:src/main/java/com/example/shop/web/OrderController.java";
	private static final String CONTROLLER = "class:com.example.shop.web.OrderController";
	private static final String CREATE = "method:com.example.shop.web.OrderController#create(OrderRequest)";
	private static final String ORDER_SERVICE = "interface:com.example.shop.service.OrderService";

	@LocalServerPort
	int port;
	@Autowired
	AnalysisService analysisService;
	@Autowired
	AnalysisRepository analysisRepository;
	@Autowired
	Neo4jClient neo4jClient;

	@MockitoBean
	GitCloner gitCloner;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void cloneTheSampleRepository() {
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1);
			FileSystemUtils.copyRecursively(SAMPLE_REPO, target);
			Files.createDirectories(target.resolve("tools"));
			Files.writeString(target.resolve("tools/util.py"), "def helper(x, y):\n    return x\n");
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	@Test
	void aClass() throws Exception {
		UUID id = completedAnalysis();

		Response response = get(id, CONTROLLER);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.string("$.id")).isEqualTo(CONTROLLER);
		assertThat(response.string("$.type")).isEqualTo("CLASS");
		assertThat(response.string("$.name")).isEqualTo("OrderController");
		assertThat(response.string("$.qualifiedName")).isEqualTo("com.example.shop.web.OrderController");
		assertThat(response.string("$.file")).isEqualTo("src/main/java/com/example/shop/web/OrderController.java");
		assertThat(response.number("$.line")).isPositive();
		assertThat(response.string("$.kind")).isEqualTo("CLASS");
		assertThat(response.string("$.parentId")).isEqualTo(CONTROLLER_FILE);
		assertThat(response.has("$.language")).isFalse();
		assertThat(response.number("$.relationships.outgoing.CONTAINS")).isPositive();       // its methods
		assertThat(response.number("$.relationships.outgoing.DEPENDS_ON")).isPositive();
		assertThat(response.has("$.relationships.incoming.CALLS")).isFalse();                 // nothing to count: absent
	}

	@Test
	void anInterfaceKnowsItsImplementors() throws Exception {
		Response response = get(completedAnalysis(), ORDER_SERVICE);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.string("$.type")).isEqualTo("INTERFACE");
		assertThat(response.number("$.relationships.incoming.IMPLEMENTS")).isEqualTo(1);     // DefaultOrderService
		assertThat(response.number("$.relationships.incoming.DEPENDS_ON")).isPositive();     // the controller
	}

	@Test
	void anEnumIsAClassWithAKindAndANestedParent() throws Exception {
		Response response = get(completedAnalysis(), "class:com.example.shop.model.Order.Status");

		assertThat(response.string("$.type")).isEqualTo("CLASS");
		assertThat(response.string("$.kind")).isEqualTo("ENUM");
		assertThat(response.string("$.parentId")).isEqualTo("class:com.example.shop.model.Order");
	}

	@Test
	void aFileHasALanguageAndAModuleParent() throws Exception {
		Response response = get(completedAnalysis(), CONTROLLER_FILE);  // slashes and a colon in the id

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.string("$.type")).isEqualTo("FILE");
		assertThat(response.string("$.language")).isEqualTo("Java");
		assertThat(response.has("$.kind")).isFalse();
		assertThat(response.string("$.parentId")).isEqualTo("module:com.example.shop.web");
		assertThat(response.string("$.file")).isEqualTo("src/main/java/com/example/shop/web/OrderController.java");
		assertThat(response.number("$.relationships.outgoing.CONTAINS")).isEqualTo(1);       // OrderController
		assertThat(response.number("$.relationships.outgoing.IMPORTS")).isPositive();
	}

	@Test
	void aMethodWhoseIdHasParenthesesAndAHash() throws Exception {
		Response response = get(completedAnalysis(), CREATE);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.string("$.id")).isEqualTo(CREATE);
		assertThat(response.string("$.type")).isEqualTo("METHOD");
		assertThat(response.string("$.name")).isEqualTo("create");
		assertThat(response.string("$.parentId")).isEqualTo(CONTROLLER);
		assertThat(response.number("$.relationships.outgoing.CALLS")).isPositive();
	}

	@Test
	void aFunctionWhoseIdHasAPathAndSpaces() throws Exception {
		UUID id = completedAnalysis();
		String functionId = neo4jClient.query("MATCH (f:Function {analysisId: $id}) WHERE NOT f.external RETURN f.nodeId AS id")
				.bind(id.toString()).to("id")
				.fetchAs(String.class).mappedBy((ts, r) -> r.get("id").asString()).one().orElseThrow();
		assertThat(functionId).contains("/").contains("#").contains("(");

		Response response = get(id, functionId);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.string("$.type")).isEqualTo("FUNCTION");
		assertThat(response.string("$.name")).isEqualTo("helper");
		assertThat(response.string("$.parentId")).isEqualTo("file:tools/util.py");
	}

	@Test
	void moduleAndRepositoryHaveNoFile() throws Exception {
		UUID id = completedAnalysis();

		Response module = get(id, "module:com.example.shop.web");
		assertThat(module.string("$.type")).isEqualTo("MODULE");
		assertThat(module.has("$.file")).isFalse();
		assertThat(module.string("$.parentId")).isEqualTo("repository:sample-shop");

		Response repository = get(id, "repository:sample-shop");
		assertThat(repository.string("$.type")).isEqualTo("REPOSITORY");
		assertThat(repository.has("$.parentId")).isFalse();
		assertThat(repository.number("$.relationships.outgoing.CONTAINS")).isPositive();
	}

	@Test
	void relationshipCountsOnlyCoverThisAnalysisAndNoExternalNodes() throws Exception {
		UUID first = completedAnalysis();
		UUID second = completedAnalysis();

		long expected = neo4jClient.query("""
						MATCH (:Class {analysisId: $id, nodeId: $node})-[r:CONTAINS]->(m {analysisId: $id})
						RETURN count(r) AS c""")
				.bind(first.toString()).to("id").bind(CONTROLLER).to("node")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElseThrow();
		assertThat(expected).isPositive();
		assertThat(get(first, CONTROLLER).number("$.relationships.outgoing.CONTAINS")).isEqualTo(expected);
		assertThat(get(second, CONTROLLER).number("$.relationships.outgoing.CONTAINS")).isEqualTo(expected); // not doubled

		// the controller references java.util / external types, which are not counted
		long externalReferences = neo4jClient.query("""
						MATCH (:Class {analysisId: $id, nodeId: $node})-[r]->(m {analysisId: $id}) WHERE m.external
						RETURN count(r) AS c""")
				.bind(first.toString()).to("id").bind("class:com.example.shop.repository.InMemoryOrderRepository").to("node")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElseThrow();
		assertThat(externalReferences).isPositive();
		long counted = ((Map<?, ?>) JsonPath.read(get(first, "class:com.example.shop.repository.InMemoryOrderRepository").body,
				"$.relationships.outgoing")).values().stream().mapToLong(value -> ((Number) value).longValue()).sum();
		long allOutgoing = neo4jClient.query("""
						MATCH (:Class {analysisId: $id, nodeId: $node})-[r]->(m {analysisId: $id})
						RETURN count(r) AS c""")
				.bind(first.toString()).to("id").bind("class:com.example.shop.repository.InMemoryOrderRepository").to("node")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElseThrow();
		assertThat(counted).isEqualTo(allOutgoing - externalReferences);
	}

	@Test
	void externalAndUnknownNodesAreNotFound() throws Exception {
		UUID id = completedAnalysis();

		for (String nodeId : new String[] {"external:class:java.util.List", "class:com.example.Nope", "nonsense", "class:",
				":x", "CLASS:com.example.shop.web.OrderController", "x".repeat(5000)}) {
			Response response = get(id, nodeId);
			assertThat(response.status()).as(nodeId).isEqualTo(404);
			assertThat(response.string("$.code")).isEqualTo("NODE_NOT_FOUND");
			assertThat(response.string("$.message")).isEqualTo("Node not found");
		}
	}

	@Test
	void unknownOrMalformedAnalysisIsNotFound() throws Exception {
		Response unknown = get(UUID.randomUUID(), CONTROLLER);
		assertThat(unknown.status()).isEqualTo(404);
		assertThat(unknown.string("$.code")).isEqualTo("ANALYSIS_NOT_FOUND");

		HttpResponse<String> malformed = http.send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/api/v1/analyses/a8f31c/nodes/" + encode(CONTROLLER))).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(malformed.statusCode()).isEqualTo(404);
		assertThat((String) JsonPath.read(malformed.body(), "$.code")).isEqualTo("ANALYSIS_NOT_FOUND");
	}

	// ---- helpers -------------------------------------------------------------------------------

	private record Response(int status, String body) {

		String string(String path) {
			return JsonPath.read(body, path);
		}

		long number(String path) {
			return ((Number) JsonPath.read(body, path)).longValue();
		}

		boolean has(String path) {
			try {
				return JsonPath.read(body, path) != null;
			}
			catch (com.jayway.jsonpath.PathNotFoundException ex) {
				return false;
			}
		}
	}

	private Response get(UUID analysisId, String nodeId) throws Exception {
		URI uri = URI.create("http://localhost:" + port + "/api/v1/analyses/" + analysisId + "/nodes/" + encode(nodeId));
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
		return new Response(response.statusCode(), response.body());
	}

	/** Percent-encodes everything but unreserved characters, like a client building the URL from an id. */
	private static String encode(String nodeId) {
		return URLEncoder.encode(nodeId, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private UUID completedAnalysis() {
		UUID id = UUID.fromString(analysisService
				.createAnalysis(new CreateAnalysisRequest("https://github.com/example/sample-shop")).analysisId());
		await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200))
				.until(() -> analysisRepository.findById(id).map(Analysis::isFinished).orElse(false));
		assertThat(analysisRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		return id;
	}
}
