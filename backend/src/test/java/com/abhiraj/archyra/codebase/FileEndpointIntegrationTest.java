package com.abhiraj.archyra.codebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
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
 * {@code GET /api/v1/analyses/{id}/files} against real PostgreSQL and Neo4j, after the real pipeline analysed
 * a fixture repository: 9 Java files under {@code src/main/java/com/example/shop} and {@code script.py}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "00000000-0000-4000-8000-0000000000a1")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FileEndpointIntegrationTest {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");
	private static final String SHOP = "src/main/java/com/example/shop";

	@Autowired
	AnalysisService analysisService;
	@Autowired
	AnalysisRepository analysisRepository;
	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	GitCloner gitCloner;

	@BeforeEach
	void cloneTheSampleRepository() {
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1);
			FileSystemUtils.copyRecursively(SAMPLE_REPO, target);
			Files.writeString(target.resolve("schema.sql"), "CREATE TABLE orders (id int);"); // counted, but no File node
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	@Test
	void withoutFiltersListsEveryFileInTheGraphSortedByPath() throws Exception {
		Page page = fetch(completedAnalysis(), "");

		assertThat(page.paths()).hasSize(10).isSorted();          // 9 Java + script.py; schema.sql has no code nodes
		assertThat(page.paths()).contains("script.py", SHOP + "/web/OrderController.java", SHOP + "/Broken.java");
		assertThat(page.totalElements()).isEqualTo(10);
		assertThat(page.totalPages()).isEqualTo(1);
		assertThat(page.file(SHOP + "/web/OrderController.java")).containsEntry("id", "file:" + SHOP + "/web/OrderController.java")
				.containsEntry("name", "OrderController.java").containsEntry("language", "Java");
		assertThat(page.file("script.py")).containsEntry("language", "Python");
	}

	@Test
	void languageFiltersTheFiles() throws Exception {
		UUID id = completedAnalysis();

		assertThat(fetch(id, "?language=Java").paths()).hasSize(9).allMatch(path -> path.endsWith(".java"));
		assertThat(fetch(id, "?language=python").paths()).containsExactly("script.py");
		assertThat(fetch(id, "?language=PYTHON").paths()).containsExactly("script.py");
		assertThat(fetch(id, "?language=Go").paths()).isEmpty();
		assertThat(fetch(id, "?language=SQL").paths()).as("SQL is counted but not part of the graph").isEmpty();
	}

	@Test
	void pathFiltersByPrefixOnWholeSegments() throws Exception {
		UUID id = completedAnalysis();

		assertThat(fetch(id, "?path=src/main").paths()).hasSize(9);
		assertThat(fetch(id, "?path=/src/main/").paths()).hasSize(9);
		assertThat(fetch(id, "?path=" + SHOP + "/model").paths()).containsExactly(
				SHOP + "/model/BaseEntity.java", SHOP + "/model/Order.java", SHOP + "/model/OrderRequest.java");
		assertThat(fetch(id, "?path=" + SHOP + "/web/OrderController.java").paths())
				.containsExactly(SHOP + "/web/OrderController.java");   // an exact file
		assertThat(fetch(id, "?path=src/ma").paths()).as("not a whole segment").isEmpty();
		assertThat(fetch(id, "?path=SRC/main").paths()).as("paths are case sensitive").isEmpty();
		assertThat(fetch(id, "?path=docs").paths()).isEmpty();
	}

	@Test
	void bothFiltersTogether() throws Exception {
		UUID id = completedAnalysis();

		assertThat(fetch(id, "?language=Java&path=" + SHOP + "/service").paths())
				.containsExactly(SHOP + "/service/DefaultOrderService.java", SHOP + "/service/OrderService.java");
		assertThat(fetch(id, "?language=Python&path=src").paths()).isEmpty();
		assertThat(fetch(id, "?language=Python&path=script.py").paths()).containsExactly("script.py");
	}

	@Test
	void pagesAreSortedAndComplete() throws Exception {
		UUID id = completedAnalysis();

		Page first = fetch(id, "?size=4&page=0");
		Page second = fetch(id, "?size=4&page=1");
		Page third = fetch(id, "?size=4&page=2");
		Page beyond = fetch(id, "?size=4&page=3");

		assertThat(first.paths()).hasSize(4);
		assertThat(second.paths()).hasSize(4);
		assertThat(third.paths()).hasSize(2);
		assertThat(beyond.paths()).isEmpty();
		assertThat(first.totalElements()).isEqualTo(10);
		assertThat(first.totalPages()).isEqualTo(3);
		assertThat(beyond.totalElements()).isEqualTo(10);
		List<String> all = new java.util.ArrayList<>(first.paths());
		all.addAll(second.paths());
		all.addAll(third.paths());
		assertThat(all).isSorted().doesNotHaveDuplicates().isEqualTo(fetch(id, "").paths());
	}

	@Test
	void totalsReflectTheFiltersNotJustThePage() throws Exception {
		Page page = fetch(completedAnalysis(), "?language=Java&size=2");

		assertThat(page.paths()).hasSize(2);
		assertThat(page.totalElements()).isEqualTo(9);
		assertThat(page.totalPages()).isEqualTo(5);
	}

	@Test
	void twoAnalysesOfTheSameRepositoryDoNotMix() throws Exception {
		UUID first = completedAnalysis();
		UUID second = completedAnalysis();

		assertThat(fetch(first, "").paths()).hasSize(10);
		assertThat(fetch(second, "").paths()).hasSize(10);
		assertThat(fetch(second, "?language=Java").totalElements()).isEqualTo(9);
	}

	@Test
	void invalidRequests() throws Exception {
		UUID id = completedAnalysis();

		expect(id, "?language=Klingon", 400, "VALIDATION_ERROR");
		expect(id, "?page=-1", 400, "VALIDATION_ERROR");
		expect(id, "?size=0", 400, "VALIDATION_ERROR");
		expect(id, "?size=201", 400, "VALIDATION_ERROR");
		expect(id, "?page=abc", 400, "VALIDATION_ERROR");
		expect(UUID.randomUUID(), "", 404, "ANALYSIS_NOT_FOUND");
		mockMvc.perform(get("/api/v1/analyses/{id}/files", "a8f31c")).andExpect(status().isNotFound());
	}

	// ---- helpers -------------------------------------------------------------------------------

	private void expect(UUID id, String query, int status, String code) throws Exception {
		String body = mockMvc.perform(get("/api/v1/analyses/{id}/files" + query, id))
				.andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(body, "$.code")).isEqualTo(code);
	}

	private Page fetch(UUID id, String query) throws Exception {
		String body = mockMvc.perform(get("/api/v1/analyses/{id}/files" + query, id))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Page(JsonPath.read(body, "$.items"), ((Number) JsonPath.read(body, "$.totalElements")).longValue(),
				((Number) JsonPath.read(body, "$.totalPages")).longValue());
	}

	private record Page(List<java.util.Map<String, Object>> items, long totalElements, long totalPages) {

		List<String> paths() {
			return items.stream().map(item -> (String) item.get("path")).toList();
		}

		java.util.Map<String, Object> file(String path) {
			return items.stream().filter(item -> path.equals(item.get("path"))).findFirst().orElseThrow();
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
}
