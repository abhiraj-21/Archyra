package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ingestion.GitCloner;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/**
 * A restored entry run for real: the real pipeline, parser, PostgreSQL and Neo4j; only the clone is replaced by a copy of a
 * fixture. The credit the deleted account spent is used (the count does not move), the graph appears only now, and a failing
 * run returns the credit exactly once like any failed analysis.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"archyra.usage.free-tier.repository-analysis-limit=5", "archyra.usage.free-tier.ai-query-limit=20" })
class RestoredEntriesPipelineIntegrationTest extends UsageFixtures {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");
	private static final String PASSWORD = "irrelevant";

	@MockitoBean
	GitCloner gitCloner;
	@Autowired
	Neo4jClient neo4j;

	@BeforeEach
	void cloneTheSampleRepository() throws Exception {
		doAnswer(invocation -> {
			FileSystemUtils.copyRecursively(SAMPLE_REPO, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	private long graphNodes(UUID analysisId) {
		return neo4j.query("MATCH (n {analysisId: $id}) RETURN count(n) AS c").bind(analysisId.toString()).to("id")
				.fetchAs(Long.class).mappedBy((type, record) -> record.get("c").asLong()).one().orElse(0L);
	}

	private int count(String table, UUID analysisId) {
		return jdbc.queryForObject("select count(*) from " + table + " where analysis_id = ?", Integer.class, analysisId);
	}

	private void awaitFinished(UUID id) {
		await().atMost(Duration.ofSeconds(120)).until(() -> analyses.findById(id).orElseThrow().isFinished());
	}

	/** The index job runs after COMPLETED; deleting the account while it still writes would only test a race. */
	private void awaitIndexed(UUID id) {
		await().atMost(Duration.ofSeconds(120)).until(() -> jdbc.queryForObject(
				"select count(*) from ai_index where analysis_id = ? and status <> 'INDEXING'", Integer.class, id) == 1);
	}

	private User analysedAndDeletedAndReturned(String email, int n, List<UUID> oldIds, List<String> urls) throws Exception {
		User user = userService.registerIfAbsent(email, "Pipeline", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		user = userService.save(user);
		for (int i = 0; i < n; i++) {
			String url = freshUrl();
			UUID id = createOk(user, url);
			awaitFinished(id);
			assertThat(analyses.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
			awaitIndexed(id);
			oldIds.add(id);
			urls.add(url);
		}
		MvcResult deleted = mockMvc.perform(post("/api/v1/users/me/delete").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}")).andReturn();
		assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
		return userService.registerIfAbsent(email, "Back", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
	}

	@Test
	void analysingAnEntryRunsTheRealPipelineOnTheSameRowWithoutChangingTheUsedCount() throws Exception {
		List<UUID> oldIds = new java.util.ArrayList<>();
		List<String> urls = new java.util.ArrayList<>();
		User returned = analysedAndDeletedAndReturned("pipe-" + UUID.randomUUID() + "@example.com", 2, oldIds, urls);

		// restored: the links only, and nothing of the old analyses is left anywhere
		List<UUID> entries = jdbc.queryForList("select id from analyses where user_id = ?", UUID.class, returned.getId());
		assertThat(entries).hasSize(2);
		for (UUID old : oldIds) {
			assertThat(graphNodes(old)).isZero();
			assertThat(count("analysis_metadata", old)).isZero();
			assertThat(count("ai_index", old)).isZero();
		}
		for (UUID entry : entries) {
			assertThat(graphNodes(entry)).isZero();
			assertThat(count("analysis_metadata", entry)).isZero();
			assertThat(count("ai_index", entry)).isZero();
			assertThat(count("code_chunks", entry)).isZero();
			assertThat(count("conversations", entry)).isZero();
		}
		assertThat(used(returned)).isEqualTo(2);

		UUID entry = entries.get(0);
		MvcResult started = mockMvc.perform(post("/api/v1/analyses/" + entry + "/analyze").header("Authorization", bearer(returned))).andReturn();
		assertThat(started.getResponse().getStatus()).isEqualTo(202);
		awaitFinished(entry);

		Analysis done = analyses.findById(entry).orElseThrow();
		assertThat(done.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(done.isQuotaCharged()).isTrue();
		assertThat(done.isQuotaRefunded()).isFalse();
		assertThat(done.getRepositoryUrl()).isIn(urls);
		assertThat(used(returned)).isEqualTo(2); // the credit already spent was used; nothing was charged
		assertThat(chargedAndNotRefunded(returned)).isEqualTo(2);
		assertThat(graphNodes(entry)).isPositive();
		assertThat(count("analysis_metadata", entry)).isPositive();
		MvcResult graph = mockMvc.perform(get("/api/v1/analyses/" + entry + "/graph").header("Authorization", bearer(returned))).andReturn();
		assertThat(graph.getResponse().getStatus()).isEqualTo(200);
		assertThat((List<?>) JsonPath.read(body(graph), "$.nodes")).isNotEmpty();
		// the other entry is untouched, and the usage view still reads two used
		UUID other = entries.get(1);
		assertThat(analyses.findById(other).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.NOT_ANALYZED);
		assertThat(graphNodes(other)).isZero();
		List<Integer> usedByType = JsonPath.read(body(usage(returned)), "$.usage[?(@.usageType=='REPOSITORY_ANALYSIS')].used");
		assertThat(usedByType).containsExactly(2);
	}

	@Test
	void aRunThatFailsReturnsTheCreditExactlyOnceAndTheRowEndsFailed() throws Exception {
		List<UUID> oldIds = new java.util.ArrayList<>();
		List<String> urls = new java.util.ArrayList<>();
		User returned = analysedAndDeletedAndReturned("pipefail-" + UUID.randomUUID() + "@example.com", 2, oldIds, urls);
		UUID entry = jdbc.queryForObject("select id from analyses where user_id = ? limit 1", UUID.class, returned.getId());
		doAnswer(invocation -> {
			throw new AnalysisFailureException("The repository could not be cloned. Check that it exists and is public.");
		}).when(gitCloner).clone(anyString(), any(Path.class));

		assertThat(mockMvc.perform(post("/api/v1/analyses/" + entry + "/analyze").header("Authorization", bearer(returned)))
				.andReturn().getResponse().getStatus()).isEqualTo(202);
		awaitFinished(entry);

		Analysis failed = analyses.findById(entry).orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(failed.isQuotaRefunded()).isTrue();
		assertThat(used(returned)).isEqualTo(1);
		assertThat(registrar.refundQuota(entry)).isFalse();
		assertThat(used(returned)).isEqualTo(1);
		assertThat(graphNodes(entry)).isZero();
	}
}
