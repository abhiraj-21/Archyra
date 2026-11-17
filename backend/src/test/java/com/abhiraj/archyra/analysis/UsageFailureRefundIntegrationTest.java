package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.ingestion.GitCloner;
import com.abhiraj.archyra.mail.MailTestConfig;

/**
 * The real pipeline on the real executor and databases; only the clone is replaced. A failing run must give its
 * unit back in the same transaction that records FAILED, so the moment a client sees FAILED the unit is back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"archyra.usage.free-tier.repository-analysis-limit=5", "archyra.usage.free-tier.ai-query-limit=20" })
class UsageFailureRefundIntegrationTest extends UsageFixtures {

	private static final Path SAMPLE_REPO = Path.of("src/test/resources/fixtures/sample-repo");

	@MockitoBean
	GitCloner gitCloner;

	private void awaitFinished(UUID id) {
		await().atMost(Duration.ofSeconds(60)).until(() -> analyses.findById(id).orElseThrow().isFinished());
	}

	@Test
	void aFailingPipelineReturnsTheUnitAtTheSameMomentItRecordsFailedAndTheUserCanRetry() throws Exception {
		var user = freeUser();
		CountDownLatch cloneStarted = new CountDownLatch(1);
		CountDownLatch letItFail = new CountDownLatch(1);
		doAnswer(invocation -> {
			cloneStarted.countDown();
			if (!letItFail.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("test did not release the clone");
			}
			throw new AnalysisFailureException("The repository could not be cloned. Check that it exists and is public.");
		}).when(gitCloner).clone(anyString(), any(Path.class));
		String url = freshUrl();

		UUID id = createOk(user, url);
		assertThat(cloneStarted.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(used(user)).isEqualTo(1); // charged while it runs
		letItFail.countDown();
		awaitFinished(id);

		assertThat(analyses.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(used(user)).isZero(); // FAILED is only visible together with the refund
		assertThat(chargedAndNotRefunded(user)).isZero();

		doAnswer(invocation -> {
			FileSystemUtils.copyRecursively(SAMPLE_REPO, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
		UUID retry = createOk(user, url);
		assertThat(retry).isNotEqualTo(id);
		awaitFinished(retry);
		assertThat(analyses.findById(retry).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(used(user)).isEqualTo(1); // the successful retry is charged, and kept
		assertThat(chargedAndNotRefunded(user)).isEqualTo(1);
	}

	@Test
	void anUnexpectedErrorInThePipelineAlsoReturnsTheUnit() throws Exception {
		var user = freeUser();
		doThrow(new IllegalStateException("disk exploded")).when(gitCloner).clone(anyString(), any(Path.class));

		UUID id = createOk(user, freshUrl());
		awaitFinished(id);

		assertThat(analyses.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(used(user)).isZero();
	}

	@Test
	void aCompletedAnalysisIsNotRefunded() throws Exception {
		var user = freeUser();
		doAnswer(invocation -> {
			FileSystemUtils.copyRecursively(SAMPLE_REPO, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));

		UUID id = createOk(user, freshUrl());
		awaitFinished(id);

		assertThat(analyses.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(used(user)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select quota_refunded from analyses where id = ?", Boolean.class, id)).isFalse();
	}

	@Test
	void startupRepairReturnsTheUnitOfAFailedAnalysisWhoseRefundWasLost() throws Exception {
		var user = freeUser();
		doThrow(new AnalysisFailureException("The repository could not be cloned. Check that it exists and is public."))
				.when(gitCloner).clone(anyString(), any(Path.class));
		UUID id = createOk(user, freshUrl());
		awaitFinished(id);
		// simulate a crash between "FAILED" and "refund": failed, charged, not refunded, counter still holding the unit
		jdbc.update("update analyses set status = 'FAILED', quota_refunded = false where id = ?", id);
		jdbc.update("update user_usage set used_count = 1 where user_id = ?", user.getId());

		recovery.failInterruptedAnalyses();

		assertThat(used(user)).isZero();
		assertThat(jdbc.queryForObject("select quota_refunded from analyses where id = ?", Boolean.class, id)).isTrue();
		recovery.failInterruptedAnalyses(); // and running it again changes nothing
		assertThat(used(user)).isZero();
	}
}
