package com.abhiraj.archyra.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.tool.schema.spi.DelayedDropRegistryNotAvailableImpl;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.ai.llm.MovableClock;
import com.abhiraj.archyra.common.UsageLimitExceededException;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManagerFactory;

/**
 * The weekly Ask AI allowance against real PostgreSQL: the counter restarts on Monday 00:00 in the configured zone
 * (Asia/Kolkata here), lazily, with the clock moved by hand and never a sleep. Repository analyses stay a lifetime
 * allowance. The limits are the real defaults (15 questions) and 5 analyses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class, MovableClockConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = { "archyra.usage.free-tier.repository-analysis-limit=5", "archyra.usage.free-tier.ai-query-limit=15" })
class WeeklyUsageIntegrationTest {

	private static final int AI = 15;
	/** Wednesday 2026-11-18 15:30 in Kolkata. */
	private static final Instant WEDNESDAY = Instant.parse("2026-11-18T10:00:00Z");
	/** Monday 2026-11-16 00:00 IST: the start of that week. */
	private static final Instant WEEK = Instant.parse("2026-11-15T18:30:00Z");
	/** Monday 2026-11-23 00:00 IST: the reset. */
	private static final Instant MONDAY = Instant.parse("2026-11-22T18:30:00Z");

	@Autowired
	UsageService usage;
	@Autowired
	MovableClock clock;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	TransactionTemplate transactions;
	@Autowired
	MockMvc mockMvc;
	@Autowired
	JwtService jwtService;
	@Autowired
	EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void aWednesday() {
		clock.setTo(WEDNESDAY);
	}

	// ---- fixtures ------------------------------------------------------------------------------

	private User newUser(Plan plan) {
		User user = userService.registerIfAbsent("weekly-" + UUID.randomUUID() + "@example.com", "Weekly Test",
				passwordEncoder.encode("irrelevant"), clock.instant()).orElseThrow();
		user.markEmailVerified();
		user.changePlan(plan, plan == Plan.PREMIUM ? clock.instant().plus(Duration.ofDays(365)) : null);
		return userService.save(user);
	}

	/** One question: lock and charge in a transaction of its own, like {@code ChatTransactions.reserve}. Returns the window. */
	private Instant ask(User user) {
		return transactions.execute(status -> {
			usage.lock(user.getId(), UsageType.AI_QUERY);
			return usage.charge(user.getId(), UsageType.AI_QUERY);
		});
	}

	private void askTimes(User user, int times) {
		for (int i = 0; i < times; i++) {
			ask(user);
		}
	}

	private void refund(User user, Instant chargedWindow) {
		transactions.executeWithoutResult(status -> usage.release(user.getId(), UsageType.AI_QUERY, chargedWindow));
	}

	private int stored(User user, String type) {
		return jdbc.query("select used_count from user_usage where user_id = ? and usage_type = ?", rs -> rs.next() ? rs.getInt(1) : -1,
				user.getId(), type);
	}

	private Instant storedWindow(User user, String type) {
		return jdbc.query("select window_start from user_usage where user_id = ? and usage_type = ?",
				rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null, user.getId(), type);
	}

	private String usageJson(User user) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/usage").header("Authorization", "Bearer " + jwtService.issueFor(user).value())).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return result.getResponse().getContentAsString();
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(String json, String usageType, String field) {
		List<Object> found = JsonPath.read(json, "$.usage[?(@.usageType=='" + usageType + "')]." + field);
		return found.isEmpty() ? null : (T) found.getFirst();
	}

	// ---- the weekly allowance ---------------------------------------------------------------------

	@Test
	void fifteenQuestionsThenARefusalWithTheResetTimeThenZeroAgainOnMonday() throws Exception {
		User user = newUser(Plan.FREE);
		askTimes(user, AI);

		assertThatThrownBy(() -> ask(user)).isInstanceOfSatisfying(UsageLimitExceededException.class, ex -> {
			assertThat(ex.resetsAt()).isEqualTo(MONDAY);
			assertThat(ex.getMessage()).contains("15 free AI queries for this week");
		});
		assertThat(stored(user, "AI_QUERY")).isEqualTo(AI);
		String usage = usageJson(user);
		assertThat((Integer) field(usage, "AI_QUERY", "used")).isEqualTo(AI);
		assertThat((Integer) field(usage, "AI_QUERY", "remaining")).isZero();
		assertThat((Boolean) field(usage, "AI_QUERY", "limitReached")).isTrue();
		assertThat((String) field(usage, "AI_QUERY", "resetsAt")).isEqualTo("2026-11-22T18:30:00Z");

		clock.setTo(MONDAY.minusSeconds(1)); // the last second of the week
		assertThatThrownBy(() -> ask(user)).isInstanceOf(UsageLimitExceededException.class);
		assertThat((Integer) field(usageJson(user), "AI_QUERY", "used")).isEqualTo(AI);

		clock.setTo(MONDAY); // exactly Monday 00:00 in Kolkata
		String reset = usageJson(user);
		assertThat((Integer) field(reset, "AI_QUERY", "used")).isZero();
		assertThat((Integer) field(reset, "AI_QUERY", "remaining")).isEqualTo(AI);
		assertThat((Boolean) field(reset, "AI_QUERY", "limitReached")).isFalse();
		assertThat((String) field(reset, "AI_QUERY", "resetsAt")).isEqualTo("2026-11-29T18:30:00Z");
		assertThat(stored(user, "AI_QUERY")).isEqualTo(AI); // reading never writes; the row rolls over when it is next charged

		assertThat(ask(user)).isEqualTo(MONDAY);
		assertThat(stored(user, "AI_QUERY")).isEqualTo(1);
		assertThat(storedWindow(user, "AI_QUERY")).isEqualTo(MONDAY);
	}

	@Test
	void aUserWhoNeverAskedHasAFreshWindowAfterTheirFirstQuestion() {
		User user = newUser(Plan.FREE);

		assertThat(ask(user)).isEqualTo(WEEK);

		assertThat(stored(user, "AI_QUERY")).isEqualTo(1);
		assertThat(storedWindow(user, "AI_QUERY")).isEqualTo(WEEK);
	}

	@Test
	void lockingAnOldRowRollsItOverInTheSameTransactionEvenWithoutCharging() {
		User user = newUser(Plan.FREE);
		askTimes(user, 3);
		clock.setTo(MONDAY.plus(Duration.ofHours(5)));

		transactions.executeWithoutResult(status -> usage.lock(user.getId(), UsageType.AI_QUERY));

		assertThat(stored(user, "AI_QUERY")).isZero();
		assertThat(storedWindow(user, "AI_QUERY")).isEqualTo(MONDAY);
	}

	@Test
	void everyWeekGivesTheFullAllowanceAndSkippedWeeksCostNothing() {
		User user = newUser(Plan.FREE);
		askTimes(user, AI);
		clock.setTo(MONDAY.plus(Duration.ofDays(21))); // three weeks later

		askTimes(user, AI);

		assertThat(stored(user, "AI_QUERY")).isEqualTo(AI);
		assertThatThrownBy(() -> ask(user)).isInstanceOf(UsageLimitExceededException.class);
	}

	@Test
	void theYearBoundaryIsJustAnotherMonday() {
		User user = newUser(Plan.FREE);
		clock.setTo(Instant.parse("2026-12-31T20:00:00Z")); // 2027-01-01 01:30 in Kolkata, still the week of 28 Dec
		askTimes(user, AI);
		clock.setTo(Instant.parse("2027-01-03T18:29:59Z")); // Sunday 23:59:59 IST
		assertThatThrownBy(() -> ask(user)).isInstanceOf(UsageLimitExceededException.class);

		clock.setTo(Instant.parse("2027-01-03T18:30:00Z")); // Monday 2027-01-04 00:00 IST

		assertThat(ask(user)).isEqualTo(Instant.parse("2027-01-03T18:30:00Z"));
		assertThat(stored(user, "AI_QUERY")).isEqualTo(1);
	}

	// ---- refunds across the reset -----------------------------------------------------------------

	@Test
	void aRefundInTheSameWeekReturnsTheUnit() {
		User user = newUser(Plan.FREE);
		askTimes(user, 2);
		Instant window = ask(user);

		refund(user, window);

		assertThat(stored(user, "AI_QUERY")).isEqualTo(2);
	}

	@Test
	void aRefundAfterTheResetIsANoOpAndNeverGoesNegative() {
		User user = newUser(Plan.FREE);
		Instant window = ask(user); // reserved on Wednesday
		clock.setTo(MONDAY.plusSeconds(1)); // the week turned while the model was thinking

		refund(user, window);
		refund(user, window);

		assertThat(stored(user, "AI_QUERY")).isEqualTo(1); // the old row is untouched: nothing to give back in a week that is over
		assertThat(ask(user)).isEqualTo(MONDAY);
		assertThat(stored(user, "AI_QUERY")).isEqualTo(1); // rolled over to 0, then this question
	}

	@Test
	void aRefundOfLastWeeksUnitNeverCreditsTheNewWeek() {
		User user = newUser(Plan.FREE);
		Instant lastWeek = ask(user);
		clock.setTo(MONDAY.plusSeconds(1));
		askTimes(user, 4); // four questions this week

		refund(user, lastWeek);

		assertThat(stored(user, "AI_QUERY")).isEqualTo(4);
	}

	@Test
	void aRefundWithoutAWindowIsANoOp() {
		User user = newUser(Plan.FREE);
		askTimes(user, 3);

		refund(user, null);

		assertThat(stored(user, "AI_QUERY")).isEqualTo(3);
	}

	@Test
	void aRefundOnAFreshRowNeverMakesTheCountNegative() {
		User user = newUser(Plan.FREE);
		Instant window = ask(user);
		refund(user, window);

		refund(user, window); // a second refund of the same unit (the caller guarantees once, the database still must not break)

		assertThat(stored(user, "AI_QUERY")).isZero();
	}

	// ---- concurrency at the rollover -----------------------------------------------------------------

	@Test
	void concurrentQuestionsAtTheRolloverInstantNeitherDoubleResetNorLoseACharge() throws Exception {
		User user = newUser(Plan.FREE);
		askTimes(user, AI);
		clock.setTo(MONDAY); // the old week is full, the new one has just begun
		int threads = 8;

		int accepted = concurrently(user, threads);

		assertThat(accepted).isEqualTo(threads);
		assertThat(stored(user, "AI_QUERY")).isEqualTo(threads); // 8, not 1 (a second reset) and not 15 + 8
		assertThat(storedWindow(user, "AI_QUERY")).isEqualTo(MONDAY);
	}

	@Test
	void concurrentQuestionsAtTheRolloverStillStopAtTheLimit() throws Exception {
		User user = newUser(Plan.FREE);
		askTimes(user, AI);
		clock.setTo(MONDAY);

		int accepted = concurrently(user, AI + 6);

		assertThat(accepted).isEqualTo(AI);
		assertThat(stored(user, "AI_QUERY")).isEqualTo(AI);
	}

	/** Runs {@code threads} questions released by a barrier; returns how many were accepted (the rest hit the limit). */
	private int concurrently(User user, int threads) throws Exception {
		CyclicBarrier together = new CyclicBarrier(threads);
		AtomicInteger accepted = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					together.await(10, TimeUnit.SECONDS);
					try {
						ask(user);
						accepted.incrementAndGet();
					}
					catch (UsageLimitExceededException expected) {
						// the limit is the point of the second test
					}
					return null;
				}));
			}
			for (Future<?> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}
		return accepted.get();
	}

	// ---- data from before the change -------------------------------------------------------------------

	@Test
	void aRowFromBeforeTheChangeHasNoWindowAndNobodyInheritsALifetimeCount() throws Exception {
		User user = newUser(Plan.FREE);
		jdbc.update("""
				insert into user_usage (id, user_id, usage_type, used_count, created_at, updated_at)
				values (?, ?, 'AI_QUERY', 20, now(), now())
				""", UUID.randomUUID(), user.getId()); // window_start stays null, like every row written before the change
		String before = usageJson(user);
		assertThat((Integer) field(before, "AI_QUERY", "used")).isZero();
		assertThat((Boolean) field(before, "AI_QUERY", "limitReached")).isFalse();

		assertThat(ask(user)).isEqualTo(WEEK);

		assertThat(stored(user, "AI_QUERY")).isEqualTo(1);
		assertThat(storedWindow(user, "AI_QUERY")).isEqualTo(WEEK);
	}

	@Test
	void theSchemaUpdateAddsTheColumnsToExistingTablesAndKeepsTheirRows() {
		User user = newUser(Plan.FREE);
		jdbc.update("insert into user_usage (id, user_id, usage_type, used_count, created_at, updated_at) values (?, ?, 'AI_QUERY', 7, now(), now())",
				UUID.randomUUID(), user.getId());
		for (String table : List.of("user_usage", "usage_ledger")) {
			jdbc.execute("alter table " + table + " drop column window_start"); // the schema as it was before the change
		}
		jdbc.execute("alter table chat_messages drop column quota_window_start");

		// the very tool `spring.jpa.hibernate.ddl-auto=update` runs at startup, over the same mapping
		var registry = entityManagerFactory.unwrap(SessionFactory.class).getSessionFactoryOptions().getServiceRegistry();
		MetadataSources sources = new MetadataSources(registry);
		entityManagerFactory.getMetamodel().getEntities().forEach(entity -> sources.addAnnotatedClass(entity.getJavaType()));
		java.util.Map<String, Object> settings = new java.util.HashMap<>(registry.requireService(ConfigurationService.class).getSettings());
		settings.put("hibernate.hbm2ddl.auto", "update");
		SchemaManagementToolCoordinator.process(sources.buildMetadata(), registry, settings, DelayedDropRegistryNotAvailableImpl.INSTANCE);

		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'user_usage' and column_name = 'window_start' and is_nullable = 'YES'", Integer.class)).isOne();
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'usage_ledger' and column_name = 'window_start' and is_nullable = 'YES'", Integer.class)).isOne();
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'chat_messages' and column_name = 'quota_window_start' and is_nullable = 'YES'", Integer.class)).isOne();
		assertThat(stored(user, "AI_QUERY")).isEqualTo(7); // the old row survived, window_start is null
		assertThat(storedWindow(user, "AI_QUERY")).isNull();
		assertThat(ask(user)).isEqualTo(WEEK); // and rolls over to 0 on first use
		assertThat(stored(user, "AI_QUERY")).isEqualTo(1);
	}

	// ---- the other types and plans ----------------------------------------------------------------------

	@Test
	void theAnalysisAllowanceIsALifetimeOneThatNeverResets() throws Exception {
		User user = newUser(Plan.FREE);
		for (int i = 0; i < 5; i++) {
			transactions.executeWithoutResult(status -> {
				usage.lock(user.getId(), UsageType.REPOSITORY_ANALYSIS);
				usage.charge(user.getId(), UsageType.REPOSITORY_ANALYSIS);
			});
		}
		clock.setTo(MONDAY.plus(Duration.ofDays(70)));

		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			usage.lock(user.getId(), UsageType.REPOSITORY_ANALYSIS);
			usage.charge(user.getId(), UsageType.REPOSITORY_ANALYSIS);
		})).isInstanceOfSatisfying(UsageLimitExceededException.class, ex -> assertThat(ex.resetsAt()).isNull());
		assertThat(stored(user, "REPOSITORY_ANALYSIS")).isEqualTo(5);
		assertThat(storedWindow(user, "REPOSITORY_ANALYSIS")).isNull();
		String json = usageJson(user);
		assertThat((Integer) field(json, "REPOSITORY_ANALYSIS", "used")).isEqualTo(5);
		assertThat((Boolean) field(json, "REPOSITORY_ANALYSIS", "limitReached")).isTrue();
	}

	@Test
	void resetsAtIsOnlyOnTheAiRowOfTheUsageResponse() throws Exception {
		User user = newUser(Plan.FREE);
		String json = usageJson(user);

		assertThat(JsonPath.<List<Object>>read(json, "$.usage[?(@.usageType=='REPOSITORY_ANALYSIS')].resetsAt")).isEmpty();
		assertThat(JsonPath.<List<String>>read(json, "$.usage[?(@.usageType=='AI_QUERY')].resetsAt")).containsExactly("2026-11-22T18:30:00Z");
		// the lifetime row has no resetsAt key at all (not even a null)
		assertThat(JsonPath.<List<Object>>read(json, "$.usage[?(@.usageType=='REPOSITORY_ANALYSIS')]").getFirst().toString()).doesNotContain("resetsAt");
	}

	@Test
	void aPremiumUserIsCountedAndNeverBlockedAndStillSeesTheResetTime() throws Exception {
		User user = newUser(Plan.PREMIUM);

		askTimes(user, AI + 5);

		assertThat(stored(user, "AI_QUERY")).isEqualTo(AI + 5);
		String json = usageJson(user);
		assertThat((Object) field(json, "AI_QUERY", "limit")).isNull();
		assertThat((Boolean) field(json, "AI_QUERY", "limitReached")).isFalse();
		assertThat((String) field(json, "AI_QUERY", "resetsAt")).isEqualTo("2026-11-22T18:30:00Z");
		clock.setTo(MONDAY);
		askTimes(user, 2); // a new week for a premium user starts at 0 as well
		assertThat(stored(user, "AI_QUERY")).isEqualTo(2);
	}

	@Test
	void aDowngradedUserKeepsTheirCountOfTheCurrentWeekAndIsBlockedUntilMonday() {
		User user = newUser(Plan.PREMIUM);
		askTimes(user, AI + 5);
		User downgraded = userService.findById(user.getId()).orElseThrow();
		downgraded.changePlan(Plan.FREE, null);
		userService.save(downgraded);

		assertThatThrownBy(() -> ask(user)).isInstanceOf(UsageLimitExceededException.class);
		assertThat(stored(user, "AI_QUERY")).isEqualTo(AI + 5); // counted this week, not forgotten

		clock.setTo(MONDAY);
		assertThat(ask(user)).isEqualTo(MONDAY); // the new week starts at 0 for them too
		assertThat(stored(user, "AI_QUERY")).isEqualTo(1);
	}
}
