package com.abhiraj.archyra.ai.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.abhiraj.archyra.ai.AiIntegrationSupport;
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.user.User;

/**
 * An index whose job was lost (the queue was full, or the server restarted) is picked up again, and the data of analyses
 * that did not complete is dropped. The queue holds one job, so a blocked worker plus one filler task fills it.
 */
@TestPropertySource(properties = "archyra.ai.index.queue-capacity=1")
class AiIndexRecoveryIntegrationTest extends AiIntegrationSupport {

	@Autowired
	IndexRecovery recovery;
	@Autowired
	EmbeddingJobs jobs;

	/** An analysis completes while the worker is busy and the queue is full: its index stays INDEXING with no job. */
	private UUID analysisWhoseJobWasRejected(User owner) throws Exception {
		CountDownLatch release = blockEmbeddingWorker();
		try {
			aiIndexExecutor.execute(() -> { }); // fills the one queue slot
			UUID id = analyzeCompleted(owner, SHOP);
			assertThat(jobs.isInFlight(id)).isFalse();
			assertThat(indexStatus(owner, id)).isEqualTo("INDEXING");
			return id;
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void theSweeperResumesAnIndexThatHasNoJob() throws Exception {
		User owner = newUser();
		UUID id = analysisWhoseJobWasRejected(owner);
		awaitIdleEmbeddingExecutor();
		assertThat(indexStatus(owner, id)).isEqualTo("INDEXING"); // nothing resumes it by itself

		recovery.sweep();

		awaitIndexStatus(owner, id, "READY");
	}

	@Test
	void startupResumesAnInterruptedIndexWhereItStopped() throws Exception {
		User owner = newUser();
		UUID id = analysisWhoseJobWasRejected(owner);
		awaitIdleEmbeddingExecutor();
		// a restart interrupted the job after the first batch: some chunks have vectors, the rest do not
		String someChunk = jdbc.queryForObject("SELECT id::text FROM code_chunks WHERE analysis_id = ? ORDER BY file, start_line LIMIT 1",
				String.class, id);
		jdbc.update("UPDATE code_chunks SET embedding = ('[' || array_to_string(array_fill(0.5::float4, ARRAY[384]), ',') || ']')::vector WHERE id = ?::uuid",
				someChunk);
		int embeddedBefore = jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ? AND embedding IS NOT NULL",
				Integer.class, id);
		assertThat(embeddedBefore).isEqualTo(1);

		recovery.onStartup();

		awaitIndexStatus(owner, id, "READY");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ? AND embedding IS NULL", Integer.class, id)).isZero();
		// the chunk that was already embedded was kept as it was, not embedded again
		assertThat(jdbc.queryForObject("SELECT (embedding <=> ('[' || array_to_string(array_fill(0.5::float4, ARRAY[384]), ',') || ']')::vector) < 0.000001 FROM code_chunks WHERE id = ?::uuid",
				Boolean.class, someChunk)).isTrue();
	}

	@Test
	void startupDropsTheDataOfAnalysesThatDidNotCompleteAndKeepsTheRest() throws Exception {
		User owner = newUser();
		UUID completed = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, completed, "READY");
		UUID queued = analyses.save(Analysis.queued(SHOP, owner.getId())).getId();
		Analysis failedRow = Analysis.queued(SHOP, owner.getId());
		failedRow.markFailed("boom");
		UUID failed = analyses.save(failedRow).getId();
		UUID vanished = UUID.randomUUID(); // an analysis whose row is gone
		for (UUID id : new UUID[] { queued, failed, vanished }) {
			jdbc.update("INSERT INTO ai_index (analysis_id, status, chunk_count, embedded_count, created_at, updated_at) VALUES (?, 'INDEXING', 1, 0, now(), now())", id);
			jdbc.update("INSERT INTO code_chunks (id, analysis_id, node_id, kind, file, start_line, end_line, content) VALUES (gen_random_uuid(), ?, 'class:X', 'TYPE_HEADER', 'X.java', 1, 2, 'class X {}')", id);
		}
		int completedChunks = chunkCount(completed);

		recovery.onStartup();

		for (UUID id : new UUID[] { queued, failed, vanished }) {
			assertThat(indexRows(id)).as("index of %s", id).isZero();
			assertThat(chunkCount(id)).as("chunks of %s", id).isZero();
		}
		assertThat(indexRows(completed)).isEqualTo(1);
		assertThat(chunkCount(completed)).isEqualTo(completedChunks);
	}

	@Test
	void theSweeperLeavesAnIndexOfAnAnalysisThatIsNotCompletedAlone() throws Exception {
		User owner = newUser();
		UUID running = analyses.save(Analysis.queued(SHOP, owner.getId())).getId();
		jdbc.update("INSERT INTO ai_index (analysis_id, status, chunk_count, embedded_count, created_at, updated_at) VALUES (?, 'INDEXING', 1, 0, now(), now())", running);
		jdbc.update("INSERT INTO code_chunks (id, analysis_id, node_id, kind, file, start_line, end_line, content) VALUES (gen_random_uuid(), ?, 'class:X', 'TYPE_HEADER', 'X.java', 1, 2, 'class X {}')", running);

		recovery.sweep();
		awaitIdleEmbeddingExecutor();

		assertThat(jobs.isInFlight(running)).isFalse();
		assertThat(jdbc.queryForObject("SELECT status FROM ai_index WHERE analysis_id = ?", String.class, running)).isEqualTo("INDEXING");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ? AND embedding IS NULL", Integer.class, running)).isEqualTo(1);
		await().atMost(Duration.ofSeconds(1)).pollDelay(Duration.ofMillis(300)).until(() -> true);
	}
}
