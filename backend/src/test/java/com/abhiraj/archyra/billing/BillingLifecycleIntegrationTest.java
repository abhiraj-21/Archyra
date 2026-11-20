package com.abhiraj.archyra.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.analysis.AnalysisActivity;
import com.abhiraj.archyra.analysis.AnalysisStatus;
import com.abhiraj.archyra.analysis.CreateAnalysisRequest;
import com.abhiraj.archyra.mail.BillingMails;
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.security.TestCaller;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;

/**
 * The end of a Premium period with a movable clock: the reminder, the expiry notice, and the grace period after which only
 * the most recently used repositories stay. Real analyses, real graphs in Neo4j, real Ask AI chunks and conversations.
 */
class BillingLifecycleIntegrationTest extends BillingIntegrationSupport {

	@Autowired
	PremiumLifecycleJob job;
	@Autowired
	AnalysisActivity activity;
	@Autowired
	Neo4jClient neo4j;
	@Autowired
	TransactionTemplate transactions;

	/** Premium of the test users ends on Monday 23 November 2026, 15:30 IST. */
	private static final Instant END = Instant.parse("2026-11-23T10:00:00Z");
	/** Three days later. */
	private static final Instant DELETION = Instant.parse("2026-11-26T10:00:00Z");

	private List<RecordingEmailService.BillingSent> sent(User user, String kind) {
		return mail.billingTo(user.getEmail(), kind);
	}

	private PremiumLifecycleJob.Run run(Instant at) {
		clock.setTo(at);
		return job.runOnce();
	}

	// ---- reminder and expiry notice -------------------------------------------------------------------------

	@Test
	void theReminderGoesOutOnceThreeDaysBeforeTheEndAndAgainForTheNextPeriod() throws Exception {
		User user = premiumUntil(END);

		run(END.minus(Duration.ofDays(3)).minusSeconds(60));
		assertThat(sent(user, "PREMIUM_REMINDER")).isEmpty();

		run(END.minus(Duration.ofDays(3)));
		run(END.minus(Duration.ofDays(3)).plus(Duration.ofMinutes(15)));
		run(END.minus(Duration.ofDays(1)));
		assertThat(sent(user, "PREMIUM_REMINDER")).hasSize(1);
		BillingMails.PremiumEnding reminder = (BillingMails.PremiumEnding) sent(user, "PREMIUM_REMINDER").getFirst().mail();
		assertThat(reminder.endsAt()).isEqualTo(END);
		assertThat(reminder.deletionAt()).isEqualTo(DELETION);
		assertThat(reminder.atRiskTotal()).isZero(); // five or fewer repositories: the email says nothing about deleting
		assertThat(reminder.renewLink()).endsWith("/upgrade");

		// renewing moves the end: the next period gets its own reminder
		pay(user, "MONTHLY");
		Instant next = expiry(user);
		assertThat(next).isEqualTo(Instant.parse("2026-12-23T10:00:00Z"));
		run(next.minus(Duration.ofDays(2)));
		assertThat(sent(user, "PREMIUM_REMINDER")).hasSize(2);
		assertThat(((BillingMails.PremiumEnding) sent(user, "PREMIUM_REMINDER").get(1).mail()).endsAt()).isEqualTo(next);
	}

	@Test
	void noReminderForFreeForPremiumWithoutAnEndOrForAnAutoRenewingSubscription() throws Exception {
		User free = newUser();
		User forever = premiumUntil(null);
		User renewing = newUser();
		String subscriptionId = json(postAs(renewing, "/api/v1/billing/subscriptions", "{\"period\":\"MONTHLY\"}"), "$.subscriptionId");
		RAZORPAY.subscriptionReads(subscriptionId, "active", Instant.parse("2026-12-20T10:00:00Z"), 1);
		String paymentId = FakeRazorpay.paymentId();
		postAs(renewing, "/api/v1/billing/subscriptions/verify", "{\"razorpaySubscriptionId\":\"" + subscriptionId + "\",\"razorpayPaymentId\":\""
				+ paymentId + "\",\"razorpaySignature\":\"" + FakeRazorpay.subscriptionSignature(paymentId, subscriptionId) + "\"}");
		Instant renewingEnd = expiry(renewing);

		run(renewingEnd.minus(Duration.ofDays(1)));
		run(renewingEnd.plus(Duration.ofMinutes(1))); // the charge has not landed yet: still nothing
		run(renewingEnd.plus(Duration.ofDays(4)));

		for (User user : List.of(free, forever, renewing)) {
			assertThat(mail.billingTo(user.getEmail()).stream().map(RecordingEmailService.BillingSent::kind))
					.doesNotContain("PREMIUM_REMINDER", "PREMIUM_EXPIRED", "REPOSITORIES_DELETED");
		}
		assertThat(reload(forever).getPlan()).isEqualTo(Plan.PREMIUM);
		assertThat(reload(renewing).getPlan()).isEqualTo(Plan.PREMIUM); // stored plan untouched while Razorpay is still charging
	}

	@Test
	void theExpiryNoticeGoesOutOnceAndRenewingDuringTheGraceResetsEverything() throws Exception {
		User user = premiumUntil(END);

		run(END);
		run(END.plus(Duration.ofMinutes(15)));
		assertThat(sent(user, "PREMIUM_EXPIRED")).hasSize(1);
		BillingMails.PremiumEnding expired = (BillingMails.PremiumEnding) sent(user, "PREMIUM_EXPIRED").getFirst().mail();
		assertThat(expired.endsAt()).isEqualTo(END);
		assertThat(expired.deletionAt()).isEqualTo(DELETION);
		assertThat((String) json(getAs(user, "/api/v1/billing/status"), "$.state")).isEqualTo("GRACE");
		assertThat((String) json(getAs(user, "/api/v1/billing/status"), "$.grace.endsAt")).isEqualTo("2026-11-26T10:00:00Z");

		clock.setTo(END.plus(Duration.ofDays(1)));
		pay(user, "MONTHLY"); // late renewal: one month from now
		assertThat(expiry(user)).isEqualTo(Instant.parse("2026-12-24T10:00:00Z"));
		run(DELETION.plus(Duration.ofHours(1)));

		assertThat(reload(user).getPlan()).isEqualTo(Plan.PREMIUM);
		assertThat(sent(user, "REPOSITORIES_DELETED")).isEmpty();
		assertThat(sent(user, "PREMIUM_EXPIRED")).hasSize(1);
		assertThat((String) json(getAs(user, "/api/v1/billing/status"), "$.state")).isEqualTo("ACTIVE");
	}

	@Test
	void anEndLongAgoIsToldFirstAndOnlyEnforcedThreeDaysAfterTheNotice() throws Exception {
		User user = premiumUntil(NOW.minus(Duration.ofDays(60))); // e.g. an administrator's grant that ran out long ago
		List<UUID> repositories = analyzeAll(user, 6);

		run(NOW);

		assertThat(analyses.findAllById(repositories)).hasSize(6);
		assertThat(sent(user, "PREMIUM_EXPIRED")).hasSize(1);
		BillingMails.PremiumEnding expired = (BillingMails.PremiumEnding) sent(user, "PREMIUM_EXPIRED").getFirst().mail();
		assertThat(expired.deletionAt()).isEqualTo(Instant.parse("2026-11-23T10:00:00Z"));
		assertThat(expired.atRiskTotal()).isEqualTo(1);
		run(Instant.parse("2026-11-23T09:59:00Z"));
		assertThat(analyses.findAllById(repositories)).hasSize(6);

		run(Instant.parse("2026-11-23T10:00:00Z"));
		assertThat(analyses.findAllById(repositories)).hasSize(5);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE);
	}

	// ---- enforcement -----------------------------------------------------------------------------------------

	@Test
	void afterTheGraceOnlyTheFiveMostRecentlyUsedStayEverywhereAndNoCreditComesBack() throws Exception {
		User user = premiumUntil(END);
		List<UUID> created = analyzeAll(user, 12); // all used at NOW: the newest created ranks first among them
		UUID failed = analyze(user, "https://github.com/example/broken-" + FakeRazorpay.random().toLowerCase());
		assertThat(analyses.findById(failed).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);

		// opening the oldest moves it up
		clock.setTo(NOW.plus(Duration.ofHours(1)));
		UUID opened = created.get(0);
		assertThat(getAs(user, "/api/v1/analyses/" + opened + "/repository").getResponse().getStatus()).isEqualTo(200);
		// a conversation on another: the Ask AI data must go with a deleted repository (creating it also opens the repository)
		UUID talkedAbout = created.get(5);
		clock.setTo(NOW.plus(Duration.ofMinutes(10))); // before the re-analysis below, so talkedAbout is not kept
		assertThat(postAs(user, "/api/v1/analyses/" + talkedAbout + "/conversations", "").getResponse().getStatus()).isEqualTo(201);
		jdbc.update("UPDATE analyses SET last_activity_at = ? WHERE id = ?", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(1))), talkedAbout);
		// re-analysing the second oldest gives a new analysis, used now
		clock.setTo(NOW.plus(Duration.ofHours(2)));
		UUID reanalysed = reanalyze(user, created.get(1));

		Set<UUID> expectedKept = Set.of(reanalysed, opened, created.get(11), created.get(10), created.get(9));
		Set<UUID> expectedDeleted = new HashSet<>(created.subList(2, 9));
		expectedDeleted.add(failed);
		assertThat(expectedDeleted).hasSize(8).contains(talkedAbout);
		assertThat(chunks(created.get(2))).isPositive();
		assertThat(graphNodes(created.get(2))).isPositive();
		assertThat(conversations(talkedAbout)).isEqualTo(1);

		// the warning and the deletion choose the same set
		clock.setTo(END.minus(Duration.ofDays(2)));
		MvcResult warning = getAs(user, "/api/v1/billing/retention");
		assertThat(new HashSet<>(this.<List<String>>jsonList(warning, "$.kept[*].analysisId"))).isEqualTo(strings(expectedKept));
		assertThat(new HashSet<>(this.<List<String>>jsonList(warning, "$.atRisk[*].analysisId"))).isEqualTo(strings(expectedDeleted));
		assertThat((Integer) json(getAs(user, "/api/v1/billing/status"), "$.grace.repositoriesAtRisk")).isEqualTo(8);

		run(END.minus(Duration.ofDays(2)));
		BillingMails.PremiumEnding reminder = (BillingMails.PremiumEnding) sent(user, "PREMIUM_REMINDER").getFirst().mail();
		assertThat(reminder.atRiskTotal()).isEqualTo(8);
		assertThat(reminder.atRisk()).hasSize(8).allMatch(name -> name.startsWith("example/"));

		// a run of a repository that is still being analysed is left alone
		CountDownLatch release = new CountDownLatch(1);
		UUID running = startBlocked(user, release);
		int usedBefore = used(user); // after the running one was charged

		try {
			run(END.plus(Duration.ofMinutes(1)));
			run(DELETION.minus(Duration.ofMinutes(1)));
			assertThat(analyses.findAllById(expectedDeleted)).hasSize(8); // not yet

			run(DELETION); // the job sweeps every user of the shared test database: assertions are about this user only
			for (UUID id : expectedDeleted) {
				assertThat(analyses.findById(id)).as("row of " + id).isEmpty();
				assertThat(graphNodes(id)).as("graph of " + id).isZero();
				assertThat(chunks(id)).as("chunks of " + id).isZero();
				assertThat(conversations(id)).isZero();
				assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_index WHERE analysis_id = ?", Integer.class, id)).isZero();
			}
			for (UUID id : expectedKept) {
				assertThat(analyses.findById(id)).as("kept " + id).isPresent();
				assertThat(graphNodes(id)).isPositive();
			}
			assertThat(analyses.findById(running)).isPresent();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_graph_purges", Integer.class)).isZero();
			assertThat(used(user)).as("no credit comes back").isEqualTo(usedBefore);
			User after = reload(user);
			assertThat(after.getPlan()).isEqualTo(Plan.FREE);
			assertThat(after.getPlanExpiresAt()).isNull();
			BillingMails.RepositoriesDeleted deleted = (BillingMails.RepositoriesDeleted) sent(user, "REPOSITORIES_DELETED").getFirst().mail();
			assertThat(deleted.deletedCount()).isEqualTo(8);
			assertThat(deleted.kept()).hasSize(5);
			assertThat((String) json(getAs(user, "/api/v1/billing/status"), "$.state")).isEqualTo("LAPSED");

			run(DELETION.plus(Duration.ofHours(1))); // nothing more
			assertThat(sent(user, "REPOSITORIES_DELETED")).hasSize(1);
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void fiveOrFewerRepositoriesLoseNothingAndThePlanStillEnds() throws Exception {
		User user = premiumUntil(END);
		List<UUID> five = analyzeAll(user, 5);

		run(END);
		run(DELETION);

		assertThat(analyses.findAllById(five)).hasSize(5);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE);
		assertThat(sent(user, "REPOSITORIES_DELETED")).isEmpty();
		assertThat(((BillingMails.PremiumEnding) sent(user, "PREMIUM_EXPIRED").getFirst().mail()).atRiskTotal()).isZero();
	}

	@Test
	void aRenewalThatCommitsWhileTheJobWaitsForTheLockStopsTheDeletion() throws Exception {
		User user = premiumUntil(END);
		List<UUID> repositories = analyzeAll(user, 7);
		run(END);
		clock.setTo(DELETION);

		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch locked = new CountDownLatch(1);
			CountDownLatch commit = new CountDownLatch(1);
			// a renewal holds the user row (as applyPayment does) and moves the end
			Future<?> renewal = threads.submit(() -> transactions.executeWithoutResult(status -> {
				User row = userService.lockForUpdate(user.getId()).orElseThrow();
				row.changePlan(Plan.PREMIUM, Instant.parse("2026-12-26T10:00:00Z"));
				locked.countDown();
				try {
					commit.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
			Future<PremiumLifecycleJob.Run> sweep = threads.submit(() -> job.runOnce());
			// the job listed the user with the old end and now waits for the row lock
			await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
					"SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class) > 0);
			commit.countDown();
			renewal.get(30, TimeUnit.SECONDS);

			sweep.get(60, TimeUnit.SECONDS);
		}
		finally {
			threads.shutdownNow();
		}
		assertThat(analyses.findAllById(repositories)).hasSize(7);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.PREMIUM);
		assertThat(sent(user, "REPOSITORIES_DELETED")).isEmpty();
	}

	@Test
	void theJobOnTwoThreadsAtOnceDeletesOnce() throws Exception {
		User user = premiumUntil(END);
		List<UUID> repositories = analyzeAll(user, 8);
		run(END);
		clock.setTo(DELETION);

		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			CyclicBarrier start = new CyclicBarrier(2);
			List<Future<PremiumLifecycleJob.Run>> both = threads.invokeAll(List.of(() -> {
				start.await(10, TimeUnit.SECONDS);
				return job.runOnce();
			}, () -> {
				start.await(10, TimeUnit.SECONDS);
				return job.runOnce();
			}));
			for (Future<PremiumLifecycleJob.Run> result : both) {
				result.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			threads.shutdownNow();
		}
		assertThat(analyses.findAllById(repositories)).hasSize(5);
		assertThat(sent(user, "REPOSITORIES_DELETED")).hasSize(1);
		assertThat(sent(user, "PREMIUM_EXPIRED")).hasSize(1);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_notices WHERE user_id = ? AND kind = 'DELETED'", Integer.class, user.getId())).isEqualTo(1);
	}

	// ---- activity ----------------------------------------------------------------------------------------------

	@Test
	void touchWritesAtMostOncePerIntervalAndARefusedOpenNeverTouches() throws Exception {
		User owner = newUser();
		UUID id = analyzeAll(owner, 1).getFirst();
		assertThat(lastActivity(id)).isEqualTo(NOW);

		clock.setTo(NOW.plus(Duration.ofMinutes(9)));
		assertThat(activity.touch(id)).isFalse();
		assertThat(lastActivity(id)).isEqualTo(NOW);
		clock.setTo(NOW.plus(Duration.ofMinutes(10)));
		assertThat(activity.touch(id)).isTrue();
		assertThat(lastActivity(id)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));

		// someone else opening it is a 404 and records nothing
		clock.setTo(NOW.plus(Duration.ofHours(5)));
		assertThat(getAs(newUser(), "/api/v1/analyses/" + id + "/repository").getResponse().getStatus()).isEqualTo(404);
		assertThat(lastActivity(id)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
		assertThat(getAs(owner, "/api/v1/analyses/" + id + "/graph").getResponse().getStatus()).isEqualTo(200);
		assertThat(lastActivity(id)).isEqualTo(NOW.plus(Duration.ofHours(5)));
	}

	// ---- helpers -------------------------------------------------------------------------------------------------

	private List<UUID> analyzeAll(User user, int count) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(analyzeCompleted(user));
		}
		return ids;
	}

	/** Any analysis, waited for until it has finished (a URL with "broken" fails at the clone). */
	private UUID analyze(User user, String url) {
		doAnswer(invocation -> {
			String requested = invocation.getArgument(0);
			if (requested.contains("broken")) {
				throw new IllegalStateException("simulated clone failure");
			}
			FileSystemUtils.copyRecursively(SAMPLE, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
		UUID id = UUID.fromString(TestCaller.runAs(user.getId(), () -> analysisService.createAnalysis(new CreateAnalysisRequest(url))).analysisId());
		await().atMost(Duration.ofSeconds(120)).until(() -> analyses.findById(id).map(a -> a.isFinished()).orElse(false));
		return id;
	}

	private UUID reanalyze(User user, UUID analysisId) {
		UUID id = UUID.fromString(TestCaller.runAs(user.getId(), () -> analysisService.reanalyze(analysisId.toString())).analysisId());
		await().atMost(Duration.ofSeconds(120)).until(() -> analyses.findById(id).map(a -> a.getStatus() == AnalysisStatus.COMPLETED).orElse(false)
				&& analyses.findById(analysisId).isEmpty());
		return id;
	}

	/** An analysis whose clone waits for {@code release}: it stays CLONING meanwhile. */
	private UUID startBlocked(User user, CountDownLatch release) {
		String url = "https://github.com/example/slow-" + FakeRazorpay.random().toLowerCase();
		doAnswer(invocation -> {
			release.await(120, TimeUnit.SECONDS);
			FileSystemUtils.copyRecursively(SAMPLE, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(argThat(requested -> requested != null && requested.contains("/slow-")), any(Path.class));
		UUID id = UUID.fromString(TestCaller.runAs(user.getId(), () -> analysisService.createAnalysis(new CreateAnalysisRequest(url))).analysisId());
		await().atMost(Duration.ofSeconds(30)).until(() -> analyses.findById(id).map(a -> a.getStatus() == AnalysisStatus.CLONING).orElse(false));
		return id;
	}

	private int used(User user) {
		return jdbc.queryForObject("SELECT used_count FROM user_usage WHERE user_id = ? AND usage_type = 'REPOSITORY_ANALYSIS'", Integer.class, user.getId());
	}

	private long graphNodes(UUID analysisId) {
		return neo4j.query("MATCH (n {analysisId: $id}) RETURN count(n) AS c").bind(analysisId.toString()).to("id")
				.fetchAs(Long.class).mappedBy((type, record) -> record.get("c").asLong()).one().orElse(0L);
	}

	private int chunks(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ?", Integer.class, analysisId);
	}

	private int conversations(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM conversations WHERE analysis_id = ?", Integer.class, analysisId);
	}

	private Instant lastActivity(UUID analysisId) {
		return jdbc.queryForObject("SELECT last_activity_at FROM analyses WHERE id = ?", java.sql.Timestamp.class, analysisId).toInstant();
	}

	private static Set<String> strings(Set<UUID> ids) {
		Set<String> out = new HashSet<>();
		ids.forEach(id -> out.add(id.toString()));
		return out;
	}

	private <T> T jsonList(MvcResult result, String path) throws Exception {
		return json(result, path);
	}
}
