package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/**
 * Real parallel requests against real PostgreSQL. A barrier releases all threads at the same instant, so the requests
 * genuinely overlap; nothing waits on a sleep. The pipeline is stubbed, so an analysis stays QUEUED.
 */
class UsageConcurrencyIntegrationTest extends UsageIntegrationSupport {

	private record Outcome(int status, String code, String analysisId) {
	}

	/** Runs every task on its own thread, started together, and collects the results. */
	private <T> List<T> inParallel(List<Callable<T>> tasks) throws Exception {
		ExecutorService threads = Executors.newFixedThreadPool(tasks.size());
		CyclicBarrier startTogether = new CyclicBarrier(tasks.size());
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(threads.submit(() -> {
					startTogether.await(30, TimeUnit.SECONDS);
					return task.call();
				}));
			}
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			threads.shutdownNow();
		}
	}

	private Callable<Outcome> post(User user, String url) {
		return () -> {
			MvcResult result = create(user, url);
			String json = body(result);
			boolean accepted = result.getResponse().getStatus() == 202;
			return new Outcome(result.getResponse().getStatus(), accepted ? null : JsonPath.read(json, "$.code"),
					accepted ? JsonPath.read(json, "$.analysisId") : null);
		};
	}

	private static long count(List<Outcome> outcomes, int status) {
		return outcomes.stream().filter(outcome -> outcome.status() == status).count();
	}

	@Test
	void parallelRequestsFromAFreeUserNeverExceedTheLimit() throws Exception {
		User user = freeUser(); // 0/limit, and no usage row yet
		User bystander = freeUser();
		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 3 * limit(); i++) {
			tasks.add(post(user, freshUrl()));
		}
		tasks.add(post(bystander, freshUrl())); // other users are not held up by the lock, or charged

		List<Outcome> outcomes = inParallel(tasks);

		List<Outcome> mine = outcomes.subList(0, 3 * limit());
		assertThat(count(mine, 202)).isEqualTo(limit());
		assertThat(mine.stream().filter(o -> o.status() == 403)).hasSize(2 * limit()).allMatch(o -> "USAGE_LIMIT_EXCEEDED".equals(o.code()));
		assertThat(used(user)).isEqualTo(limit());
		assertThat(analysisRows(user)).isEqualTo(limit());
		assertThat(chargedAndNotRefunded(user)).isEqualTo(limit());
		assertThat(outcomes.getLast().status()).isEqualTo(202);
		assertThat(used(bystander)).isEqualTo(1);
	}

	@Test
	void oneUnitLeftMeansExactlyOneParallelRequestWins() throws Exception {
		User user = freeUser();
		for (int i = 0; i < limit() - 1; i++) {
			createOk(user, freshUrl());
		}
		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(post(user, freshUrl()));
		}

		List<Outcome> outcomes = inParallel(tasks);

		assertThat(count(outcomes, 202)).isEqualTo(1);
		assertThat(count(outcomes, 403)).isEqualTo(7);
		assertThat(used(user)).isEqualTo(limit());
		assertThat(analysisRows(user)).isEqualTo(limit());
	}

	@Test
	void parallelRequestsForTheSameRepositoryMakeOneAnalysisAndOneCharge() throws Exception {
		User user = freeUser();
		String url = freshUrl();
		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(post(user, url));
		}

		List<Outcome> outcomes = inParallel(tasks);

		assertThat(count(outcomes, 202)).isEqualTo(8);
		Set<String> ids = outcomes.stream().map(Outcome::analysisId).collect(Collectors.toSet());
		assertThat(ids).hasSize(1);
		assertThat(analysisRows(user)).isEqualTo(1);
		assertThat(used(user)).isEqualTo(1);
		verify(pipeline, times(1)).run(any());
	}

	@Test
	void theVeryFirstRequestsRaceWithoutAConstraintViolation() throws Exception {
		User user = premiumUser(); // no cap in the way: every request must succeed, so any 500 is a real defect
		assertThat(usageRows(user)).isZero();
		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(post(user, freshUrl()));
		}

		List<Outcome> outcomes = inParallel(tasks);

		assertThat(count(outcomes, 202)).isEqualTo(8);
		assertThat(usageRows(user)).isEqualTo(1);
		assertThat(used(user)).isEqualTo(8);
	}

	@Test
	void createsRacingWithRefundsLeaveACounterThatMatchesTheChargedAnalyses() throws Exception {
		User user = freeUser();
		List<UUID> earlier = new ArrayList<>();
		for (int i = 0; i < limit() - 1; i++) {
			earlier.add(createOk(user, freshUrl()));
		}

		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			tasks.add(post(user, freshUrl()));
		}
		// every earlier analysis fails from two threads at once: the second refund of each must do nothing
		for (UUID id : earlier) {
			for (int i = 0; i < 2; i++) {
				tasks.add(() -> {
					try {
						registrar.failAndRefund(id, "boom");
					}
					catch (IllegalStateException alreadyFinished) {
						registrar.refundQuota(id); // the loser of the race still asks for the refund, which must be a no-op
					}
					return new Outcome(0, null, null);
				});
			}
		}

		List<Outcome> outcomes = inParallel(tasks);

		List<Outcome> creates = outcomes.subList(0, 6);
		assertThat(creates).allMatch(o -> o.status() == 202 || (o.status() == 403 && "USAGE_LIMIT_EXCEEDED".equals(o.code())));
		assertThat(used(user)).isBetween(0, limit());
		assertThat(used(user)).isEqualTo(chargedAndNotRefunded(user));
		assertThat(chargedAndNotRefunded(user)).isEqualTo((int) count(creates, 202));
	}
}
