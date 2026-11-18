package com.abhiraj.archyra.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
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
 * The {@code test} flag on a real graph: the real pipeline analyses a fixture with {@code src/main} and
 * {@code src/test} Java (including a package called {@code test} inside main code) and a Python project with
 * {@code tests/}, and the {@code /graph}, {@code /nodes/{id}} and {@code /files} endpoints are read back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "00000000-0000-4000-8000-0000000000a1")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TestFlagIntegrationTest {

	private static final Path FIXTURE = Path.of("src/test/resources/fixtures/tests-and-main");
	private static final String TEST_JAVA = "src/test/java/com/acme/app/GreetingServiceTest.java";
	private static final String SAMPLES_JAVA = "src/test/java/com/acme/app/fixtures/Samples.java";
	private static final String TEST_PY = "tests/test_cleaner.py";
	private static final List<String> MAIN_FILES = List.of("src/main/java/com/acme/app/GreetingRepository.java",
			"src/main/java/com/acme/app/GreetingService.java", "src/main/java/com/acme/app/test/Support.java", "tools/cleaner.py");

	@Autowired
	AnalysisService analysisService;
	@Autowired
	AnalysisRepository analysisRepository;
	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	GitCloner gitCloner;

	@BeforeEach
	void cloneTheFixture() {
		doAnswer(invocation -> {
			FileSystemUtils.copyRecursively(FIXTURE, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	// ---- /graph ---------------------------------------------------------------------------------------------

	@Test
	void everyNodeInTheGraphCarriesTheFlagAndItFollowsTheDeclaringFile() throws Exception {
		UUID id = completedAnalysis();

		String body = json(id, "/graph");
		List<Map<String, Object>> nodes = JsonPath.read(body, "$.nodes");

		assertThat(nodes).isNotEmpty().allSatisfy(node -> assertThat(node.get("test")).isInstanceOf(Boolean.class));
		for (Map<String, Object> node : nodes) {
			Object file = node.get("file");
			String type = (String) node.get("type");
			if (file != null) {
				boolean underTests = file.equals(TEST_JAVA) || file.equals(SAMPLES_JAVA) || file.equals(TEST_PY);
				assertThat(node.get("test")).as("%s declared in %s", node.get("id"), file).isEqualTo(underTests);
			}
			else if (type.equals("REPOSITORY")) {
				assertThat(node.get("test")).isEqualTo(false);
			}
		}
		assertThat(flag(nodes, "class:com.acme.app.GreetingServiceTest")).isTrue();
		assertThat(flag(nodes, "class:com.acme.app.fixtures.Samples")).isTrue();
		assertThat(flag(nodes, "class:com.acme.app.GreetingService")).isFalse();
		assertThat(flag(nodes, "interface:com.acme.app.GreetingRepository")).isFalse();
		// a package called "test" in src/main is not test code
		assertThat(flag(nodes, "class:com.acme.app.test.Support")).isFalse();
		assertThat(flag(nodes, "file:" + TEST_JAVA)).isTrue();
		assertThat(flag(nodes, "file:src/main/java/com/acme/app/test/Support.java")).isFalse();
	}

	@Test
	void methodsAndFunctionsFollowTheirFile() throws Exception {
		UUID id = completedAnalysis();

		List<Map<String, Object>> nodes = JsonPath.read(json(id, "/graph"), "$.nodes");

		List<Map<String, Object>> codeInTests = nodes.stream()
				.filter(n -> n.get("type").equals("METHOD") || n.get("type").equals("FUNCTION"))
				.filter(n -> n.get("file") != null && (n.get("file").equals(TEST_JAVA) || n.get("file").equals(TEST_PY))).toList();
		List<Map<String, Object>> codeInMain = nodes.stream()
				.filter(n -> n.get("type").equals("METHOD") || n.get("type").equals("FUNCTION"))
				.filter(n -> n.get("file") != null && !n.get("file").equals(TEST_JAVA) && !n.get("file").equals(TEST_PY)
						&& !n.get("file").equals(SAMPLES_JAVA)).toList();
		assertThat(codeInTests).as("test_clean() and greetsByName()").hasSizeGreaterThanOrEqualTo(2)
				.allSatisfy(n -> assertThat(n.get("test")).isEqualTo(true));
		assertThat(codeInMain).isNotEmpty().allSatisfy(n -> assertThat(n.get("test")).isEqualTo(false));
		assertThat(nodes).anySatisfy(n -> assertThat(n.get("type")).isEqualTo("FUNCTION"));
	}

	@Test
	void aModuleIsATestOnlyWhenEveryFileInItIsATest() throws Exception {
		UUID id = completedAnalysis();

		List<Map<String, Object>> nodes = JsonPath.read(json(id, "/graph?nodeType=MODULE"), "$.nodes");

		// com.acme.app holds GreetingService (main) and GreetingServiceTest (test): mixed
		assertThat(flag(nodes, "module:com.acme.app")).isFalse();
		// only Samples.java lives in com.acme.app.fixtures, and it is a test file
		assertThat(flag(nodes, "module:com.acme.app.fixtures")).isTrue();
		// the package called "test" holds a src/main file only
		assertThat(flag(nodes, "module:com.acme.app.test")).isFalse();
		assertThat(nodes).allSatisfy(n -> assertThat(n.get("test")).isInstanceOf(Boolean.class));
	}

	@Test
	void theRepositoryNodeIsNeverATest() throws Exception {
		UUID id = completedAnalysis();

		List<Map<String, Object>> nodes = JsonPath.read(json(id, "/graph?nodeType=REPOSITORY"), "$.nodes");

		assertThat(nodes).hasSize(1).allSatisfy(n -> assertThat(n.get("test")).isEqualTo(false));
	}

	// ---- /nodes/{id} ----------------------------------------------------------------------------------------

	@Test
	void nodeDetailCarriesTheFlagForClassesFilesAndModules() throws Exception {
		UUID id = completedAnalysis();

		assertThat(node(id, "class:com.acme.app.GreetingServiceTest")).isTrue();
		assertThat(node(id, "class:com.acme.app.GreetingService")).isFalse();
		assertThat(node(id, "class:com.acme.app.test.Support")).isFalse();
		assertThat(node(id, "file:" + TEST_PY)).isTrue();
		assertThat(node(id, "file:tools/cleaner.py")).isFalse();
		assertThat(node(id, "module:com.acme.app")).isFalse();
		assertThat(node(id, "module:com.acme.app.fixtures")).isTrue();
		String repository = JsonPath.<List<String>>read(json(id, "/graph?nodeType=REPOSITORY"), "$.nodes[*].id").get(0);
		assertThat(node(id, repository)).isFalse();
	}

	// ---- /files ---------------------------------------------------------------------------------------------

	@Test
	void everyFileItemAndTheFileDetailCarryTheFlag() throws Exception {
		UUID id = completedAnalysis();

		Map<String, Boolean> flags = filesFlags(json(id, "/files"));

		assertThat(flags).containsEntry(TEST_JAVA, true).containsEntry(SAMPLES_JAVA, true).containsEntry(TEST_PY, true);
		MAIN_FILES.forEach(path -> assertThat(flags).containsEntry(path, false));
		assertThat(flags).hasSize(7);

		String detail = mockMvc.perform(get(URI.create(base(id) + "/files/" + encode("file:" + TEST_PY))))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<Boolean>read(detail, "$.test")).isTrue();
		String mainDetail = mockMvc.perform(get(URI.create(base(id) + "/files/" + encode("file:tools/cleaner.py"))))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<Boolean>read(mainDetail, "$.test")).isFalse();
	}

	@Test
	void testFilterKeepsOnlyTestOrOnlyNonTestFilesAndIsCaseInsensitive() throws Exception {
		UUID id = completedAnalysis();

		assertThat(filesFlags(json(id, "/files?test=true"))).containsOnlyKeys(TEST_JAVA, SAMPLES_JAVA, TEST_PY)
				.containsValues(true, true, true);
		assertThat(filesFlags(json(id, "/files?test=false")).keySet()).containsExactlyInAnyOrderElementsOf(MAIN_FILES);
		assertThat(filesFlags(json(id, "/files?test=TRUE"))).hasSize(3);
		assertThat(filesFlags(json(id, "/files"))).hasSize(7);
		assertThat(filesFlags(json(id, "/files?test="))).as("blank means both").hasSize(7);
	}

	@Test
	void testFilterCombinesWithLanguageAndPath() throws Exception {
		UUID id = completedAnalysis();

		assertThat(filesFlags(json(id, "/files?test=true&language=Python")).keySet()).containsExactly(TEST_PY);
		assertThat(filesFlags(json(id, "/files?test=false&language=Python")).keySet()).containsExactly("tools/cleaner.py");
		assertThat(filesFlags(json(id, "/files?test=true&language=Java")).keySet()).containsExactlyInAnyOrder(TEST_JAVA, SAMPLES_JAVA);
		assertThat(filesFlags(json(id, "/files?test=true&path=src/test")).keySet()).containsExactlyInAnyOrder(TEST_JAVA, SAMPLES_JAVA);
		assertThat(filesFlags(json(id, "/files?test=true&path=src/main"))).isEmpty();
		assertThat(filesFlags(json(id, "/files?test=false&path=src/main"))).hasSize(3);
		assertThat(filesFlags(json(id, "/files?test=true&language=Go"))).isEmpty();
	}

	@Test
	void theTestFilterIsAppliedBeforePaginationSoTheTotalsCountTheMatchingFiles() throws Exception {
		UUID id = completedAnalysis();

		// 4 non-test files, 2 per page
		String first = json(id, "/files?test=false&page=0&size=2");
		String second = json(id, "/files?test=false&page=1&size=2");
		String beyond = json(id, "/files?test=false&page=2&size=2");
		assertThat(JsonPath.<Integer>read(first, "$.totalElements")).isEqualTo(4);
		assertThat(JsonPath.<Integer>read(first, "$.totalPages")).isEqualTo(2);
		assertThat(JsonPath.<List<?>>read(first, "$.items")).hasSize(2);
		assertThat(JsonPath.<List<?>>read(second, "$.items")).hasSize(2);
		assertThat(JsonPath.<Integer>read(second, "$.page")).isEqualTo(1);
		assertThat(JsonPath.<List<?>>read(beyond, "$.items")).isEmpty();
		assertThat(JsonPath.<Integer>read(beyond, "$.totalElements")).isEqualTo(4);

		// pages are disjoint and together cover exactly the non-test files, in path order
		List<String> paged = new java.util.ArrayList<>(JsonPath.<List<String>>read(first, "$.items[*].path"));
		paged.addAll(JsonPath.<List<String>>read(second, "$.items[*].path"));
		assertThat(paged).containsExactlyElementsOf(MAIN_FILES.stream().sorted().toList());

		// 3 test files, 2 per page: a last page of 1
		String testsSecond = json(id, "/files?test=true&page=1&size=2");
		assertThat(JsonPath.<Integer>read(testsSecond, "$.totalElements")).isEqualTo(3);
		assertThat(JsonPath.<Integer>read(testsSecond, "$.totalPages")).isEqualTo(2);
		assertThat(JsonPath.<List<?>>read(testsSecond, "$.items")).hasSize(1);

		// without the filter nothing changes: 7 files, 4 pages of 2
		String all = json(id, "/files?page=0&size=2");
		assertThat(JsonPath.<Integer>read(all, "$.totalElements")).isEqualTo(7);
		assertThat(JsonPath.<Integer>read(all, "$.totalPages")).isEqualTo(4);
	}

	@Test
	void anInvalidTestValueIsAValidationErrorListingTheAllowedValues() throws Exception {
		UUID id = completedAnalysis();

		for (String bad : List.of("maybe", "1", "yes", "tru", "true;")) {
			mockMvc.perform(get(URI.create(base(id) + "/files?test=" + URLEncoder.encode(bad, StandardCharsets.UTF_8))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
					.andExpect(jsonPath("$.message").value("Invalid value for test. Allowed values: true, false"));
		}
	}

	// ---- helpers --------------------------------------------------------------------------------------------

	private static Boolean flag(List<Map<String, Object>> nodes, String id) {
		return nodes.stream().filter(n -> n.get("id").equals(id)).map(n -> (Boolean) n.get("test")).findFirst()
				.orElseThrow(() -> new AssertionError("no node " + id + " in " + nodes.stream().map(n -> n.get("id")).toList()));
	}

	private static Map<String, Boolean> filesFlags(String page) {
		List<Map<String, Object>> items = JsonPath.read(page, "$.items");
		return items.stream().collect(Collectors.toMap(i -> (String) i.get("path"), i -> (Boolean) i.get("test")));
	}

	private Boolean node(UUID id, String nodeId) throws Exception {
		String body = mockMvc.perform(get(URI.create(base(id) + "/nodes/" + encode(nodeId))))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.test");
	}

	private static String encode(String nodeId) {
		return URLEncoder.encode(nodeId, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String base(UUID id) {
		return "/api/v1/analyses/" + id;
	}

	private String json(UUID id, String pathAndQuery) throws Exception {
		return mockMvc.perform(get(URI.create(base(id) + pathAndQuery))).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	private UUID completedAnalysis() {
		UUID id = UUID.fromString(analysisService
				.createAnalysis(new CreateAnalysisRequest("https://github.com/example/tests-and-main-" + UUID.randomUUID().toString().substring(0, 8))).analysisId());
		await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200))
				.until(() -> analysisRepository.findById(id).map(Analysis::isFinished).orElse(false));
		assertThat(analysisRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		return id;
	}
}
