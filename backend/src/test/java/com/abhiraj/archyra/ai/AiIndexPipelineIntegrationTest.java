package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.analysis.AnalysisStatus;
import com.abhiraj.archyra.common.AnalysisFailed;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/** The index lifecycle through the real pipeline: capture, embedding, failures, retry, deletion and replacement. */
class AiIndexPipelineIntegrationTest extends AiIntegrationSupport {

	private int used(User user, String usageType) throws Exception {
		MvcResult usage = getAs(user, "/api/v1/usage");
		List<Integer> used = JsonPath.read(usage.getResponse().getContentAsString(), "$.usage[?(@.usageType=='" + usageType + "')].used");
		return used.getFirst();
	}

	@Test
	void aNewAnalysisIndexesItselfInTheBackgroundAndEndsReadyWithRealVectors() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);

		awaitIndexStatus(owner, id, "READY");

		assertThat(chunkCount(id)).isPositive();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ? AND embedding IS NULL",
				Integer.class, id)).isZero();
		assertThat(jdbc.queryForList("SELECT DISTINCT vector_dims(embedding) FROM code_chunks WHERE analysis_id = ?",
				Integer.class, id)).containsExactly(384);
	}

	@Test
	void theAnalysisNeverWaitsForEmbedding() throws Exception {
		User owner = newUser();
		CountDownLatch release = blockEmbeddingWorker();
		UUID id;
		try {
			id = analyzeCompleted(owner, SHOP); // returns although no embedding can start

			assertThat(indexStatus(owner, id)).isEqualTo("INDEXING");
			MvcResult index = getAs(owner, indexPath(id));
			assertThat((int) json(index, "$.chunkCount")).isPositive();
			assertThat((int) json(index, "$.embeddedCount")).isZero();
		}
		finally {
			release.countDown();
		}
		awaitIndexStatus(owner, id, "READY");
	}

	@Test
	void testCodeIsNotIndexed() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, TESTS_AND_MAIN);
		awaitIndexStatus(owner, id, "READY");

		List<String> files = jdbc.queryForList("SELECT DISTINCT file FROM code_chunks WHERE analysis_id = ?", String.class, id);

		assertThat(files).contains("src/main/java/com/acme/app/GreetingService.java")
				.noneMatch(file -> file.contains("src/test/") || file.contains("tests/"));
	}

	@Test
	void aRepositoryOfOnlyTestCodeIsEmptyAndTheAnalysisStillCompletes() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, ONLY_TESTS);

		MvcResult index = getAs(owner, indexPath(id));

		assertThat((String) json(index, "$.status")).isEqualTo("EMPTY");
		assertThat((String) json(index, "$.reason")).isEqualTo("This repository has no non-test code to ask about.");
		assertThat((int) json(index, "$.chunkCount")).isZero();
		assertThat((boolean) json(index, "$.retryable")).isFalse();
		MvcResult retry = postAs(owner, indexPath(id));
		assertThat(retry.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) json(retry, "$.status")).isEqualTo("EMPTY");
	}

	@Test
	void aChunkCaptureFailureNeverFailsOrRefundsTheAnalysisAndNeedsAReanalysis() throws Exception {
		User owner = newUser();
		int analysesUsedBefore = used(owner, "REPOSITORY_ANALYSIS");
		jdbc.execute("ALTER TABLE code_chunks ADD CONSTRAINT it_no_rows CHECK (false) NOT VALID"); // every insert fails
		UUID id;
		try {
			id = analyze(owner, SHOP);
		}
		finally {
			jdbc.execute("ALTER TABLE code_chunks DROP CONSTRAINT it_no_rows");
		}

		Analysis analysis = analyses.findById(id).orElseThrow();
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.isQuotaRefunded()).isFalse();
		assertThat(used(owner, "REPOSITORY_ANALYSIS")).isEqualTo(analysesUsedBefore + 1);
		MvcResult index = getAs(owner, indexPath(id));
		assertThat((String) json(index, "$.status")).isEqualTo("FAILED");
		assertThat((int) json(index, "$.chunkCount")).isZero();
		assertThat((boolean) json(index, "$.retryable")).isFalse();
		assertThat((String) json(index, "$.reason")).contains("Re-analyze");
		assertThat(chunkCount(id)).isZero();
		MvcResult retry = postAs(owner, indexPath(id));
		assertThat(retry.getResponse().getStatus()).isEqualTo(409);
		assertThat((String) json(retry, "$.code")).isEqualTo("AI_REANALYZE_REQUIRED");
	}

	@Test
	void aFailedEmbeddingIsRetryableForFreeAndResumesToReady() throws Exception {
		User owner = newUser();
		UUID id;
		jdbc.execute("ALTER TABLE code_chunks ADD CONSTRAINT it_no_vectors CHECK (embedding IS NULL) NOT VALID"); // every vector write fails
		try {
			id = analyzeCompleted(owner, SHOP);
			awaitIndexStatus(owner, id, "FAILED");
		}
		finally {
			jdbc.execute("ALTER TABLE code_chunks DROP CONSTRAINT it_no_vectors");
		}
		assertThat(analyses.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		MvcResult failed = getAs(owner, indexPath(id));
		assertThat((boolean) json(failed, "$.retryable")).isTrue();
		assertThat((String) json(failed, "$.reason")).contains("retry");
		assertThat((int) json(failed, "$.chunkCount")).isPositive();
		int analysesUsed = used(owner, "REPOSITORY_ANALYSIS");
		int questionsUsed = used(owner, "AI_QUERY");

		MvcResult retry = postAs(owner, indexPath(id));

		assertThat(retry.getResponse().getStatus()).isEqualTo(202);
		assertThat((String) json(retry, "$.status")).isEqualTo("INDEXING");
		awaitIndexStatus(owner, id, "READY");
		assertThat(used(owner, "REPOSITORY_ANALYSIS")).isEqualTo(analysesUsed); // a retry costs nothing
		assertThat(used(owner, "AI_QUERY")).isEqualTo(questionsUsed);
		assertThat(postAs(owner, indexPath(id)).getResponse().getStatus()).isEqualTo(200); // idempotent once READY
		assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ? AND embedding IS NULL",
				Integer.class, id)).isZero();
	}

	@Test
	void deletingAnAnalysisRemovesItsIndexAndChunksButNotOthers() throws Exception {
		User owner = newUser();
		UUID doomed = analyzeCompleted(owner, SHOP);
		UUID kept = analyzeCompleted(owner, TESTS_AND_MAIN);
		awaitIndexStatus(owner, doomed, "READY");
		awaitIndexStatus(owner, kept, "READY");
		int keptChunks = chunkCount(kept);

		assertThat(deleteAs(owner, "/api/v1/analyses/" + doomed).getResponse().getStatus()).isEqualTo(204);

		assertThat(indexRows(doomed)).isZero();
		assertThat(chunkCount(doomed)).isZero();
		assertThat(indexRows(kept)).isEqualTo(1);
		assertThat(chunkCount(kept)).isEqualTo(keptChunks);
	}

	@Test
	void deletingWhileEmbeddingStopsQuietlyAndNothingComesBack() throws Exception {
		User owner = newUser();
		CountDownLatch release = blockEmbeddingWorker();
		UUID id;
		try {
			id = analyzeCompleted(owner, SHOP);
			assertThat(indexStatus(owner, id)).isEqualTo("INDEXING");

			assertThat(deleteAs(owner, "/api/v1/analyses/" + id).getResponse().getStatus()).isEqualTo(204);
		}
		finally {
			release.countDown();
		}
		awaitIdleEmbeddingExecutor(); // the queued job ran, found the analysis gone and stopped

		assertThat(analyses.findById(id)).isEmpty();
		assertThat(indexRows(id)).isZero();
		assertThat(chunkCount(id)).isZero();
	}

	@Test
	void aCompletedRerunRemovesTheReplacedAnalysesAiDataAndIndexesTheNewOne() throws Exception {
		User owner = newUser();
		UUID old = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, old, "READY");

		MvcResult rerun = postAs(owner, "/api/v1/analyses/" + old + "/reanalyze");
		assertThat(rerun.getResponse().getStatus()).isEqualTo(202);
		UUID replacement = UUID.fromString(json(rerun, "$.analysisId"));
		await().atMost(Duration.ofSeconds(120)).until(() -> analyses.findById(old).isEmpty()
				&& analyses.findById(replacement).map(a -> a.getStatus() == AnalysisStatus.COMPLETED).orElse(false));

		assertThat(indexRows(old)).isZero();
		assertThat(chunkCount(old)).isZero();
		awaitIndexStatus(owner, replacement, "READY");
		assertThat(chunkCount(replacement)).isPositive();
	}

	@Test
	void aFailedAnalysisEventDropsWhatWasCaptured() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");

		events.publishEvent(new AnalysisFailed(id));

		assertThat(indexRows(id)).isZero();
		assertThat(chunkCount(id)).isZero();
	}
}
