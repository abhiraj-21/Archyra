package com.abhiraj.archyra.codebase;

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
import java.util.List;
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
 * {@code /files/{fileId}} and {@code /classes/{classId}} over real HTTP against real PostgreSQL and Neo4j,
 * after the real pipeline analysed a fixture repository (plus {@code tools/util.py}). Only the GitHub clone
 * is replaced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FileAndClassEndpointHttpTest {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");
	private static final String SHOP = "src/main/java/com/example/shop";
	private static final String CONTROLLER_FILE = "file:" + SHOP + "/web/OrderController.java";
	private static final String CONTROLLER = "class:com.example.shop.web.OrderController";
	private static final String ORDER = "class:com.example.shop.model.Order";
	private static final String BASE_ENTITY = "class:com.example.shop.model.BaseEntity";
	private static final String ORDER_SERVICE = "interface:com.example.shop.service.OrderService";
	private static final String ORDER_REPOSITORY = "interface:com.example.shop.repository.OrderRepository";

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

	// ---- files ---------------------------------------------------------------------------------

	@Test
	void aFileListsItsClassMethodsImportsAndReferences() throws Exception {
		Response file = get(completedAnalysis(), "files", CONTROLLER_FILE);

		assertThat(file.status()).isEqualTo(200);
		assertThat(file.string("$.id")).isEqualTo(CONTROLLER_FILE);
		assertThat(file.string("$.name")).isEqualTo("OrderController.java");
		assertThat(file.string("$.path")).isEqualTo(SHOP + "/web/OrderController.java");
		assertThat(file.string("$.language")).isEqualTo("Java");
		assertThat(file.string("$.module.id")).isEqualTo("module:com.example.shop.web");
		assertThat(file.string("$.module.type")).isEqualTo("MODULE");

		assertThat(file.ids("$.classes")).containsExactly(CONTROLLER);
		assertThat(file.string("$.classes[0].kind")).isEqualTo("CLASS");
		assertThat(file.string("$.classes[0].parentId")).isEqualTo(CONTROLLER_FILE);
		assertThat(file.number("$.classes[0].line")).isPositive();
		assertThat(file.list("$.interfaces")).isEmpty();
		assertThat(file.list("$.functions")).isEmpty();
		assertThat(file.names("$.methods")).contains("create", "createDefault", "log", "print");
		assertThat(file.strings("$.methods[*].parentId")).containsOnly(CONTROLLER);

		// imports are other files of the repository, with their paths
		assertThat(file.ids("$.imports")).containsExactlyInAnyOrder(
				"file:" + SHOP + "/model/Order.java", "file:" + SHOP + "/model/OrderRequest.java",
				"file:" + SHOP + "/service/OrderService.java");
		assertThat(file.strings("$.imports[*].type")).containsOnly("FILE");
		assertThat(file.strings("$.imports[*].file")).contains(SHOP + "/service/OrderService.java");

		// references are the types its declarations use, none of them declared in this file
		assertThat(file.ids("$.references")).contains(ORDER, "class:com.example.shop.model.OrderRequest", ORDER_SERVICE)
				.doesNotContain(CONTROLLER);
	}

	@Test
	void externalImportsAreMarkedAndNamed() throws Exception {
		Response file = get(completedAnalysis(), "files", "file:" + SHOP + "/model/Order.java");

		assertThat(file.status()).isEqualTo(200);
		assertThat(file.ids("$.classes")).containsExactly(ORDER, "class:com.example.shop.model.Order.Status");
		assertThat(file.list("$.classes[?(@.id=='class:com.example.shop.model.Order.Status')].kind")).containsExactly("ENUM");
		assertThat(file.list("$.classes[?(@.id=='class:com.example.shop.model.Order.Status')].parentId")).containsExactly(ORDER);
		Map<String, Object> list = file.maps("$.imports[?(@.id=='external:class:java.util.List')]").get(0);
		assertThat(list).containsEntry("external", true).containsEntry("qualifiedName", "java.util.List")
				.containsEntry("name", "List").doesNotContainKey("file");
		assertThat(file.ids("$.imports")).contains("external:class:java.util.ArrayList");
		assertThat(file.ids("$.references")).as("a type declared in the file itself is not a reference")
				.doesNotContain("class:com.example.shop.model.Order.Status", ORDER);
	}

	@Test
	void anInterfaceFile() throws Exception {
		Response file = get(completedAnalysis(), "files", "file:" + SHOP + "/service/OrderService.java");

		assertThat(file.ids("$.interfaces")).containsExactly(ORDER_SERVICE);
		assertThat(file.list("$.classes")).isEmpty();
		assertThat(file.names("$.methods")).containsExactly("place");
		assertThat(file.strings("$.methods[*].parentId")).containsExactly(ORDER_SERVICE);
	}

	@Test
	void aFileOfALanguageWithFunctions() throws Exception {
		UUID id = completedAnalysis();
		Response file = get(id, "files", "file:tools/util.py");

		assertThat(file.status()).isEqualTo(200);
		assertThat(file.string("$.language")).isEqualTo("Python");
		assertThat(file.list("$.classes")).isEmpty();
		assertThat(file.names("$.functions")).containsExactly("helper");
		assertThat(file.string("$.functions[0].parentId")).isEqualTo("file:tools/util.py");
		assertThat(file.map("$.module")).containsKey("id");

		Response script = get(id, "files", "file:script.py");   // a script with no declarations at all
		assertThat(script.status()).isEqualTo(200);
		assertThat(script.list("$.classes")).isEmpty();
		assertThat(script.list("$.methods")).isEmpty();
		assertThat(script.list("$.functions")).isEmpty();
		assertThat(script.list("$.imports")).isEmpty();
		assertThat(script.list("$.references")).isEmpty();
	}

	@Test
	void everyFileOfTheListingCanBeOpened() throws Exception {
		UUID id = completedAnalysis();
		Response listing = get(id, "files", null);
		List<String> ids = listing.strings("$.items[*].id");
		assertThat(ids).hasSize(11);   // 9 Java + script.py + tools/util.py

		for (String fileId : ids) {
			assertThat(get(id, "files", fileId).status()).as(fileId).isEqualTo(200);
		}
	}

	@Test
	void notFilesAreNotFound() throws Exception {
		UUID id = completedAnalysis();

		for (String fileId : new String[] {"file:src/Nope.java", "nonsense", "file:", CONTROLLER, "module:com.example.shop.web",
				"external:class:java.util.List", "FILE:" + SHOP + "/web/OrderController.java"}) {
			Response response = get(id, "files", fileId);
			assertThat(response.status()).as(fileId).isEqualTo(404);
			assertThat(response.string("$.code")).isEqualTo("NODE_NOT_FOUND");
			assertThat(response.string("$.message")).isEqualTo("File not found");
		}
	}

	// ---- classes -------------------------------------------------------------------------------

	@Test
	void aClassListsMethodsAndDependencies() throws Exception {
		UUID id = completedAnalysis();
		Response response = get(id, "classes", CONTROLLER);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.string("$.id")).isEqualTo(CONTROLLER);
		assertThat(response.string("$.name")).isEqualTo("OrderController");
		assertThat(response.string("$.qualifiedName")).isEqualTo("com.example.shop.web.OrderController");
		assertThat(response.string("$.file")).isEqualTo(SHOP + "/web/OrderController.java");
		assertThat(response.string("$.kind")).isEqualTo("CLASS");
		assertThat(response.number("$.line")).isPositive();
		assertThat(response.string("$.parentId")).isEqualTo(CONTROLLER_FILE);
		assertThat(response.list("$.superclasses")).isEmpty();
		assertThat(response.list("$.interfaces")).isEmpty();
		assertThat(response.names("$.methods")).contains("create", "createDefault", "log", "print");
		assertThat(response.ids("$.dependencies")).contains(ORDER_SERVICE, ORDER, "class:com.example.shop.model.OrderRequest")
				.doesNotContain(ORDER_REPOSITORY);           // no layer skipping
		assertThat(response.list("$.dependencies[*].external")).containsOnly(false);

		// the listed methods are exactly the Method nodes the class contains
		long contained = neo4jClient.query("""
						MATCH (:Class {analysisId: $id, nodeId: $node})-[:CONTAINS]->(m:Method) RETURN count(m) AS c""")
				.bind(id.toString()).to("id").bind(CONTROLLER).to("node")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElseThrow();
		assertThat(response.list("$.methods")).hasSize((int) contained);
	}

	@Test
	void aClassListsItsInterfacesAndSuperclasses() throws Exception {
		UUID id = completedAnalysis();

		Response service = get(id, "classes", "class:com.example.shop.service.DefaultOrderService");
		assertThat(service.ids("$.interfaces")).containsExactly(ORDER_SERVICE);
		assertThat(service.string("$.interfaces[0].type")).isEqualTo("INTERFACE");
		assertThat(service.string("$.interfaces[0].file")).isEqualTo(SHOP + "/service/OrderService.java");
		assertThat(service.ids("$.dependencies")).contains(ORDER_REPOSITORY, ORDER);
		assertThat(service.ids("$.superclasses")).isEmpty();

		Response order = get(id, "classes", ORDER);
		assertThat(order.ids("$.superclasses")).containsExactly(BASE_ENTITY);
		assertThat(order.ids("$.interfaces")).isEmpty();
		assertThat(order.strings("$.dependencies[*].id")).as("external types such as java.util.List are not dependencies")
				.noneMatch(dependency -> dependency.startsWith("external:"));
	}

	@Test
	void aNestedEnumIsAClassWithItsEnclosingClassAsParent() throws Exception {
		Response status = get(completedAnalysis(), "classes", "class:com.example.shop.model.Order.Status");

		assertThat(status.status()).isEqualTo(200);
		assertThat(status.string("$.kind")).isEqualTo("ENUM");
		assertThat(status.string("$.parentId")).isEqualTo(ORDER);
	}

	@Test
	void anImplementationOfARepositoryInterface() throws Exception {
		Response response = get(completedAnalysis(), "classes", "class:com.example.shop.repository.InMemoryOrderRepository");

		assertThat(response.ids("$.interfaces")).containsExactly(ORDER_REPOSITORY);
		assertThat(response.names("$.methods")).contains("save");
	}

	@Test
	void twoAnalysesOfTheSameRepositoryDoNotMix() throws Exception {
		UUID first = completedAnalysis();
		UUID second = completedAnalysis();

		Response a = get(first, "classes", CONTROLLER);
		Response b = get(second, "classes", CONTROLLER);
		assertThat(b.list("$.methods")).hasSameSizeAs(a.list("$.methods")).isNotEmpty();
		assertThat(b.ids("$.dependencies")).containsExactlyInAnyOrderElementsOf(a.ids("$.dependencies"));
		assertThat(b.ids("$.methods")).doesNotHaveDuplicates();

		Response fileA = get(first, "files", CONTROLLER_FILE);
		Response fileB = get(second, "files", CONTROLLER_FILE);
		assertThat(fileB.list("$.methods")).hasSameSizeAs(fileA.list("$.methods"));
		assertThat(fileB.list("$.imports")).hasSameSizeAs(fileA.list("$.imports"));
		assertThat(fileB.list("$.references")).hasSameSizeAs(fileA.list("$.references"));
	}

	@Test
	void notClassesAreNotFound() throws Exception {
		UUID id = completedAnalysis();

		for (String classId : new String[] {"class:com.example.Nope", "nonsense", "class:", ORDER_SERVICE, CONTROLLER_FILE,
				"method:com.example.shop.web.OrderController#create(OrderRequest)", "external:class:java.util.List",
				"CLASS:com.example.shop.web.OrderController"}) {
			Response response = get(id, "classes", classId);
			assertThat(response.status()).as(classId).isEqualTo(404);
			assertThat(response.string("$.code")).isEqualTo("NODE_NOT_FOUND");
			assertThat(response.string("$.message")).isEqualTo("Class not found");
		}
	}

	@Test
	void unknownOrMalformedAnalysisIsNotFound() throws Exception {
		for (String resource : new String[] {"files/" + encode(CONTROLLER_FILE), "classes/" + encode(CONTROLLER)}) {
			HttpResponse<String> unknown = send("/api/v1/analyses/" + UUID.randomUUID() + "/" + resource);
			assertThat(unknown.statusCode()).isEqualTo(404);
			assertThat((String) JsonPath.read(unknown.body(), "$.code")).isEqualTo("ANALYSIS_NOT_FOUND");

			HttpResponse<String> malformed = send("/api/v1/analyses/a8f31c/" + resource);
			assertThat(malformed.statusCode()).isEqualTo(404);
			assertThat((String) JsonPath.read(malformed.body(), "$.code")).isEqualTo("ANALYSIS_NOT_FOUND");
		}
	}

	// ---- helpers -------------------------------------------------------------------------------

	private record Response(int status, String body) {

		String string(String path) {
			return JsonPath.read(body, path);
		}

		long number(String path) {
			return ((Number) JsonPath.read(body, path)).longValue();
		}

		List<Object> list(String path) {
			return JsonPath.read(body, path);
		}

		Map<String, Object> map(String path) {
			return JsonPath.read(body, path);
		}

		List<Map<String, Object>> maps(String path) {
			return JsonPath.read(body, path);
		}

		List<String> strings(String path) {
			return JsonPath.read(body, path);
		}

		List<String> ids(String listPath) {
			return strings(listPath + "[*].id");
		}

		List<String> names(String listPath) {
			return strings(listPath + "[*].name");
		}
	}

	/** {@code id} null lists the resource, otherwise it opens that one. */
	private Response get(UUID analysisId, String resource, String id) throws Exception {
		HttpResponse<String> response = send("/api/v1/analyses/" + analysisId + "/" + resource
				+ (id == null ? "?size=200" : "/" + encode(id)));
		return new Response(response.statusCode(), response.body());
	}

	private HttpResponse<String> send(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	/** Percent-encodes everything but unreserved characters, like a client building the URL from an id. */
	private static String encode(String id) {
		return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
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
