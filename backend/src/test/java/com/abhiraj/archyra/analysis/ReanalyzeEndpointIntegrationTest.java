package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/** {@code POST /analyses/{id}/reanalyze} through the real chain and database, with the pipeline stubbed. */
class ReanalyzeEndpointIntegrationTest extends UsageIntegrationSupport {

	private MvcResult reanalyze(User user, Object analysisId) throws Exception {
		return mockMvc.perform(post("/api/v1/analyses/" + analysisId + "/reanalyze").header("Authorization", bearer(user))).andReturn();
	}

	private UUID reanalyzeOk(User user, UUID analysisId) throws Exception {
		MvcResult result = reanalyze(user, analysisId);
		if (result.getResponse().getStatus() != 202) {
			throw new AssertionError("Expected 202 but got " + result.getResponse().getStatus() + ": " + body(result));
		}
		return UUID.fromString(JsonPath.read(body(result), "$.analysisId"));
	}

	private UUID finishedAnalysis(User user, AnalysisStatus status) throws Exception {
		UUID id = createOk(user, freshUrl());
		finish(id, status);
		return id;
	}

	private UUID replacesOf(UUID id) {
		return analyses.findById(id).orElseThrow().getReplacesAnalysisId();
	}

	// ---- the happy path ------------------------------------------------------------------------

	@Test
	void reanalyzingACompletedAnalysisStartsANewOneForTheSameRepositoryAndKeepsTheOldUntilItCompletes() throws Exception {
		User user = freeUser();
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);
		String url = analyses.findById(old).orElseThrow().getRepositoryUrl();

		MvcResult result = reanalyze(user, old);

		assertThat(result.getResponse().getStatus()).isEqualTo(202);
		assertThat((Integer) JsonPath.read(body(result), "$.length()")).isEqualTo(2);
		assertThat((String) JsonPath.read(body(result), "$.status")).isEqualTo("QUEUED");
		UUID fresh = UUID.fromString(JsonPath.read(body(result), "$.analysisId"));
		assertThat(fresh).isNotEqualTo(old);
		Analysis created = analyses.findById(fresh).orElseThrow();
		assertThat(created.getRepositoryUrl()).isEqualTo(url);
		assertThat(created.getOwnerId()).isEqualTo(user.getId());
		assertThat(created.getReplacesAnalysisId()).isEqualTo(old);
		assertThat(created.isQuotaCharged()).isTrue();
		assertThat(analyses.findById(old)).isPresent(); // only a completed re-run removes it
		verify(pipeline).run(fresh);
	}

	@Test
	void aFailedAnalysisCanBeRetriedThroughReanalyze() throws Exception {
		User user = freeUser();
		UUID failed = createOk(user, freshUrl());
		registrar.failAndRefund(failed, "boom");
		assertThat(used(user)).isZero();

		UUID retry = reanalyzeOk(user, failed);

		assertThat(retry).isNotEqualTo(failed);
		assertThat(replacesOf(retry)).isEqualTo(failed);
		assertThat(used(user)).isEqualTo(1);
	}

	// ---- usage and plans -----------------------------------------------------------------------

	@Test
	void aReanalysisCostsOneUnitLikeANewAnalysis() throws Exception {
		User user = freeUser();
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);
		assertThat(used(user)).isEqualTo(1);

		reanalyzeOk(user, old);

		assertThat(used(user)).isEqualTo(2);
		assertThat(chargedAndNotRefunded(user)).isEqualTo(2);
	}

	@Test
	void aFreeUserAtTheLimitCannotReanalyzeAndNothingIsCreated() throws Exception {
		User user = freeUser();
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);
		for (int i = 1; i < limit(); i++) {
			createOk(user, freshUrl());
		}

		MvcResult refused = reanalyze(user, old);

		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(refused)).isEqualTo("USAGE_LIMIT_EXCEEDED");
		assertThat(used(user)).isEqualTo(limit());
		assertThat(analysisRows(user)).isEqualTo(limit());
		assertThat(analyses.findById(old)).isPresent();
	}

	@Test
	void aPremiumUserPastTheFreeLimitIsNeverBlocked() throws Exception {
		User user = premiumUser();
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);
		for (int i = 0; i < limit() + 2; i++) {
			createOk(user, freshUrl());
		}

		UUID fresh = reanalyzeOk(user, old);

		assertThat(replacesOf(fresh)).isEqualTo(old);
		assertThat(used(user)).isEqualTo(limit() + 4);
	}

	@Test
	void anExpiredPremiumPlanFallsBackToTheFreeLimit() throws Exception {
		User user = newUser(Plan.PREMIUM, Instant.now().plus(Duration.ofDays(1)));
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);
		for (int i = 0; i < limit(); i++) {
			createOk(user, freshUrl());
		}
		user.changePlan(Plan.PREMIUM, Instant.now().minus(Duration.ofMinutes(1)));
		userService.save(user);

		assertThat(code(reanalyze(user, old))).isEqualTo("USAGE_LIMIT_EXCEEDED");
	}

	// ---- running analyses and duplicates -------------------------------------------------------

	@Test
	void reanalyzingARunningAnalysisReturnsItWithoutAChargeOrAPipeline() throws Exception {
		User user = freeUser();
		UUID running = createOk(user, freshUrl());

		MvcResult again = reanalyze(user, running);

		assertThat(again.getResponse().getStatus()).isEqualTo(202);
		assertThat((String) JsonPath.read(body(again), "$.analysisId")).isEqualTo(running.toString());
		assertThat((String) JsonPath.read(body(again), "$.status")).isEqualTo("QUEUED");
		assertThat(used(user)).isEqualTo(1);
		assertThat(analysisRows(user)).isEqualTo(1);
		verify(pipeline, times(1)).run(any());
	}

	@Test
	void reanalyzingTwiceWhileTheFirstRerunIsRunningReturnsTheSameRerun() throws Exception {
		User user = freeUser();
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);

		UUID first = reanalyzeOk(user, old);
		UUID second = reanalyzeOk(user, old);

		assertThat(second).isEqualTo(first);
		assertThat(used(user)).isEqualTo(2);
		assertThat(analysisRows(user)).isEqualTo(2);
		verify(pipeline, times(2)).run(any()); // the original and one re-run, never two re-runs
	}

	@Test
	void parallelReanalyzeRequestsStartExactlyOnePipeline() throws Exception {
		User user = freeUser();
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CyclicBarrier startTogether = new CyclicBarrier(threads);
		List<Future<String>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				Callable<String> call = () -> {
					startTogether.await(30, TimeUnit.SECONDS);
					MvcResult result = reanalyze(user, old);
					assertThat(result.getResponse().getStatus()).isEqualTo(202);
					return JsonPath.read(body(result), "$.analysisId");
				};
				futures.add(pool.submit(call));
			}
			List<String> ids = new ArrayList<>();
			for (Future<String> future : futures) {
				ids.add(future.get(60, TimeUnit.SECONDS));
			}
			assertThat(ids.stream().distinct()).hasSize(1);
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(analysisRows(user)).isEqualTo(2);
		assertThat(used(user)).isEqualTo(2);
		verify(pipeline, times(2)).run(any());
	}

	@Test
	void afterTheRerunFinishesTheNextReanalyzeIsANewRun() throws Exception {
		User user = freeUser();
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);
		UUID first = reanalyzeOk(user, old);
		finish(first, AnalysisStatus.COMPLETED);

		UUID second = reanalyzeOk(user, first);

		assertThat(second).isNotIn(old, first);
		assertThat(replacesOf(second)).isEqualTo(first);
	}

	// ---- ownership and ids ---------------------------------------------------------------------

	@Test
	void anotherUsersAnalysisIsNotFoundAndNothingIsCharged() throws Exception {
		User owner = freeUser();
		User other = freeUser();
		UUID theirs = finishedAnalysis(owner, AnalysisStatus.COMPLETED);

		MvcResult result = reanalyze(other, theirs);

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(code(result)).isEqualTo("ANALYSIS_NOT_FOUND");
		assertThat(used(other)).isZero();
		assertThat(analysisRows(other)).isZero();
		assertThat(used(owner)).isEqualTo(1);
	}

	@Test
	void anUnknownOrMalformedIdIsNotFound() throws Exception {
		User user = freeUser();

		assertThat(reanalyze(user, UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);
		assertThat(reanalyze(user, "not-a-uuid").getResponse().getStatus()).isEqualTo(404);
		assertThat(usageRows(user)).isZero();
	}

	@Test
	void reanalyzeNeedsAToken() throws Exception {
		assertThat(mockMvc.perform(post("/api/v1/analyses/" + UUID.randomUUID() + "/reanalyze")).andReturn().getResponse().getStatus())
				.isEqualTo(401);
	}

	// ---- executor rejection --------------------------------------------------------------------

	@Test
	void aRejectedReanalysisIs503ReturnsTheUnitAndLeavesTheOldAnalysisAlone() throws Exception {
		User user = freeUser();
		UUID old = finishedAnalysis(user, AnalysisStatus.COMPLETED);
		doThrow(new TaskRejectedException("queue full")).when(pipeline).run(any());

		MvcResult busy = reanalyze(user, old);

		assertThat(busy.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(busy)).isEqualTo("SERVICE_BUSY");
		assertThat(used(user)).isEqualTo(1); // only the original analysis is charged
		assertThat(chargedAndNotRefunded(user)).isEqualTo(1);
		assertThat(analyses.findById(old).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(jdbc.queryForObject("select count(*) from analyses where user_id = ? and replaces_analysis_id = ? and status = 'FAILED' and quota_refunded",
				Integer.class, user.getId(), old)).isEqualTo(1);
	}
}
