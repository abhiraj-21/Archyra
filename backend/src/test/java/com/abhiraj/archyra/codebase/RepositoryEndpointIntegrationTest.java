package com.abhiraj.archyra.codebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.analysis.AnalysisRepository;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.analysis.AnalysisStatus;
import com.abhiraj.archyra.analysis.CreateAnalysisRequest;
import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ingestion.GitCloner;

/**
 * {@code GET /api/v1/analyses/{id}/repository} against real PostgreSQL and Neo4j, after the real pipeline
 * analysed a fixture repository. Only the GitHub clone is replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "00000000-0000-4000-8000-0000000000a1")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RepositoryEndpointIntegrationTest {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");
	private static final String URL = "https://github.com/example/sample-shop";

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

	/** Files a test adds to the cloned repository (path to content), on top of the sample repository. */
	private final Map<String, String> extraFiles = new LinkedHashMap<>();

	@BeforeEach
	void cloneTheSampleRepository() {
		extraFiles.clear();
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1);
			FileSystemUtils.copyRecursively(SAMPLE_REPO, target);
			Files.writeString(target.resolve("schema.sql"), "CREATE TABLE orders (id int);"); // counted, not in the graph
			for (Map.Entry<String, String> file : extraFiles.entrySet()) {
				Path path = target.resolve(file.getKey());
				Files.createDirectories(path.getParent());
				Files.writeString(path, file.getValue());
			}
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	@Test
	void describesTheAnalysedRepository() throws Exception {
		UUID id = completedAnalysis();

		mockMvc.perform(get("/api/v1/analyses/{id}/repository", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("sample-shop"))
				.andExpect(jsonPath("$.languages.length()").value(3))
				.andExpect(jsonPath("$.languages[0]").value("Java"))          // 9 files
				.andExpect(jsonPath("$.languages[1]").value("Python"))        // 1 file, before SQL by name
				.andExpect(jsonPath("$.languages[2]").value("SQL"))           // counted only, no code nodes
				.andExpect(jsonPath("$.files").value(11))                     // 9 Java + script.py + schema.sql
				.andExpect(jsonPath("$.classes").value(count(id, "Class")))
				.andExpect(jsonPath("$.methods").value(count(id, "Method") + count(id, "Function")))
				.andExpect(jsonPath("$.technologies").isArray())
				.andExpect(jsonPath("$.length()").value(6));
	}

	@Test
	void returnsTheDetectedTechnologiesWithTheirCategoriesByCategoryThenName() throws Exception {
		extraFiles.put("pom.xml", """
				<project>
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>com.example</groupId>
				  <artifactId>sample-shop</artifactId>
				  <dependencies>
				    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
				    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
				    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId></dependency>
				  </dependencies>
				</project>
				""");
		extraFiles.put(".github/workflows/ci.yml", "name: ci\non: push\n");
		extraFiles.put("Dockerfile", "FROM scratch\n");
		UUID id = completedAnalysis();

		mockMvc.perform(get("/api/v1/analyses/{id}/repository", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.technologies.length()").value(6))
				.andExpect(jsonPath("$.technologies[0].name").value("Spring Boot"))
				.andExpect(jsonPath("$.technologies[0].category").value("BACKEND_FRAMEWORK"))
				.andExpect(jsonPath("$.technologies[1].name").value("Maven"))
				.andExpect(jsonPath("$.technologies[1].category").value("BUILD_TOOL"))
				.andExpect(jsonPath("$.technologies[2].name").value("GitHub Actions"))
				.andExpect(jsonPath("$.technologies[2].category").value("CI_CD"))
				.andExpect(jsonPath("$.technologies[3].name").value("PostgreSQL"))
				.andExpect(jsonPath("$.technologies[3].category").value("DATABASE"))
				.andExpect(jsonPath("$.technologies[4].name").value("Docker"))
				.andExpect(jsonPath("$.technologies[4].category").value("INFRASTRUCTURE"))
				.andExpect(jsonPath("$.technologies[5].name").value("JUnit"))
				.andExpect(jsonPath("$.technologies[5].category").value("TESTING"))
				.andExpect(jsonPath("$.technologies[0].length()").value(2)); // name and category only, no evidence or ecosystem
	}

	@Test
	void aRepositoryWithNoDetectedTechnologiesHasAnEmptyListNotNullAndNotMissing() throws Exception {
		UUID id = completedAnalysis();

		mockMvc.perform(get("/api/v1/analyses/{id}/repository", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.technologies").isArray())
				.andExpect(jsonPath("$.technologies.length()").value(0))
				.andExpect(jsonPath("$.technologies").exists());
	}

	@Test
	void anotherUsersAnalysisIsNotFoundEvenWhenItHasTechnologies() throws Exception {
		extraFiles.put("Dockerfile", "FROM scratch\n");
		UUID id = completedAnalysis();

		mockMvc.perform(get("/api/v1/analyses/{id}/repository", id).with(user("00000000-0000-4000-8000-0000000000b2")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"))
				.andExpect(jsonPath("$.technologies").doesNotExist());
	}

	@Test
	void countsOnlyTheRepositoriesOwnCodeAndOnlyThisAnalysis() throws Exception {
		UUID first = completedAnalysis();
		UUID second = completedAnalysis();

		long ownClasses = count(first, "Class");
		long allClassNodes = neo4jClient.query("MATCH (n:Class {analysisId: $id}) RETURN count(n) AS c")
				.bind(first.toString()).to("id")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElseThrow();
		assertThat(allClassNodes).as("the graph holds external classes such as java.util.List").isGreaterThan(ownClasses);

		for (UUID id : new UUID[] {first, second}) {
			mockMvc.perform(get("/api/v1/analyses/{id}/repository", id))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.classes").value(ownClasses));
		}
	}

	@Test
	void failedAnalysisReturns409() throws Exception {
		doThrow(new AnalysisFailureException("The repository could not be cloned."))
				.when(gitCloner).clone(anyString(), any(Path.class));
		UUID id = startAnalysis();
		awaitFinished(id);

		mockMvc.perform(get("/api/v1/analyses/{id}/repository", id))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
	}

	@Test
	void unknownAnalysisReturns404() throws Exception {
		mockMvc.perform(get("/api/v1/analyses/{id}/repository", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
	}

	@Test
	void malformedAnalysisIdReturns404() throws Exception {
		mockMvc.perform(get("/api/v1/analyses/{id}/repository", "a8f31c"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
	}

	// ---- helpers -------------------------------------------------------------------------------

	private UUID startAnalysis() {
		return UUID.fromString(analysisService.createAnalysis(new CreateAnalysisRequest(URL)).analysisId());
	}

	private UUID completedAnalysis() {
		UUID id = startAnalysis();
		assertThat(awaitFinished(id).getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		return id;
	}

	private Analysis awaitFinished(UUID id) {
		await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200))
				.until(() -> analysisRepository.findById(id).map(Analysis::isFinished).orElse(false));
		return analysisRepository.findById(id).orElseThrow();
	}

	/** Independent count of a label's own (non-external) nodes. */
	private long count(UUID id, String label) {
		return neo4jClient.query("MATCH (n:" + label + " {analysisId: $id}) WHERE n.external = false RETURN count(n) AS c")
				.bind(id.toString()).to("id")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(-1L);
	}
}
