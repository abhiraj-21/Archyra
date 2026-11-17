package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ingestion.GitCloner;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/**
 * Re-running with the real pipeline, executor and databases (only the clone is replaced): a completed re-run
 * replaces its predecessor completely, a failed one leaves it alone, and a free user gets the same behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"archyra.usage.free-tier.repository-analysis-limit=5", "archyra.usage.free-tier.ai-query-limit=20" })
class ReanalyzeReplacementIntegrationTest extends UsageFixtures {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");

	@MockitoBean
	GitCloner gitCloner;
	@Autowired
	Neo4jClient neo4j;

	private void cloneTheSampleRepository() {
		doAnswer(invocation -> {
			FileSystemUtils.copyRecursively(SAMPLE_REPO, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	private void awaitFinished(UUID id) {
		await().atMost(Duration.ofSeconds(60)).until(() -> analyses.findById(id).orElseThrow().isFinished());
	}

	private long graphNodes(UUID analysisId) {
		return neo4j.query("MATCH (n {analysisId: $id}) RETURN count(n) AS c").bind(analysisId.toString()).to("id")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(-1L);
	}

	private UUID reanalyzeOk(User user, UUID id) throws Exception {
		var result = mockMvc.perform(post("/api/v1/analyses/" + id + "/reanalyze").header("Authorization", bearer(user))).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(202);
		return UUID.fromString(JsonPath.read(body(result), "$.analysisId"));
	}

	@Test
	void aCompletedRerunReplacesTheOldAnalysisItsGraphAndItsMetadata() throws Exception {
		cloneTheSampleRepository();
		User user = freeUser(); // free users get the replacement too
		UUID old = createOk(user, freshUrl());
		awaitFinished(old);
		assertThat(analyses.findById(old).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(graphNodes(old)).isPositive();
		assertThat(jdbc.queryForObject("select count(*) from analysis_metadata where analysis_id = ?", Integer.class, old)).isPositive();

		UUID fresh = reanalyzeOk(user, old);
		awaitFinished(fresh);

		assertThat(analyses.findById(fresh).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		await().atMost(Duration.ofSeconds(30)).until(() -> analyses.findById(old).isEmpty());
		assertThat(graphNodes(old)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from analysis_metadata where analysis_id = ?", Integer.class, old)).isZero();
		assertThat(graphNodes(fresh)).isPositive();
		assertThat(jdbc.queryForObject("select count(*) from analysis_metadata where analysis_id = ?", Integer.class, fresh)).isPositive();
		assertThat(analysisRows(user)).isEqualTo(1);
		assertThat(used(user)).isEqualTo(1); // the re-run was charged, and the replaced analysis' credit came back
		assertThat(chargedAndNotRefunded(user)).isEqualTo(1);
	}

	@Test
	void aFailedRerunKeepsTheOldAnalysisAndItsResultsAndReturnsItsUnit() throws Exception {
		cloneTheSampleRepository();
		User user = freeUser();
		UUID old = createOk(user, freshUrl());
		awaitFinished(old);
		long oldNodes = graphNodes(old);
		doAnswer(invocation -> {
			throw new AnalysisFailureException("The repository could not be cloned. Check that it exists and is public.");
		}).when(gitCloner).clone(anyString(), any(Path.class));

		UUID rerun = reanalyzeOk(user, old);
		awaitFinished(rerun);

		assertThat(analyses.findById(rerun).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analyses.findById(old).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(graphNodes(old)).isEqualTo(oldNodes).isPositive();
		assertThat(used(user)).isEqualTo(1); // the failed re-run's unit is back
		assertThat(chargedAndNotRefunded(user)).isEqualTo(1);
	}

	@Test
	void retryingAFailedAnalysisReplacesTheFailedRecord() throws Exception {
		doAnswer(invocation -> {
			throw new AnalysisFailureException("The repository could not be cloned. Check that it exists and is public.");
		}).when(gitCloner).clone(anyString(), any(Path.class));
		User user = freeUser();
		UUID failed = createOk(user, freshUrl());
		awaitFinished(failed);
		cloneTheSampleRepository();

		UUID retry = reanalyzeOk(user, failed);
		awaitFinished(retry);

		assertThat(analyses.findById(retry).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		await().atMost(Duration.ofSeconds(30)).until(() -> analyses.findById(failed).isEmpty());
		assertThat(analysisRows(user)).isEqualTo(1);
		assertThat(used(user)).isEqualTo(1);
	}
}
