package com.abhiraj.archyra.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.ai.llm.MovableClock;
import com.abhiraj.archyra.usage.MovableClockConfig;
import com.abhiraj.archyra.usage.UsageLedgerService;
import com.abhiraj.archyra.usage.UsageResponse;
import com.abhiraj.archyra.usage.UsageService;
import com.abhiraj.archyra.usage.UsageType;
import com.jayway.jsonpath.JsonPath;

/**
 * Deleting an account without resetting what it consumed (spec/account-deletion), through the real filter chain,
 * PostgreSQL, bcrypt and the mail recorder. The scenarios that need real analyses, graphs and Ask AI data are in
 * {@code AccountDeletionPurgeIntegrationTest}; here the consumption is charged through {@link UsageService}, the same
 * path every real charge takes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class, MovableClockConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = { "archyra.usage.free-tier.repository-analysis-limit=3", "archyra.usage.free-tier.ai-query-limit=4" })
class AccountDeletionIntegrationTest {

	private static final String PASSWORD = "correct horse battery";
	private static final int ANALYSES = 3;
	private static final int QUESTIONS = 4;

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	UserRepository users;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JwtService jwtService;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	UsageService usage;
	@Autowired
	UsageLedgerService ledger;
	@Autowired
	IdentityLinkService linking;
	@Autowired
	RecordingEmailService mail;
	@Autowired
	TransactionTemplate transactions;
	@Autowired
	SecurityProperties security;
	@Autowired
	MovableClock clock;

	/** Wednesday 2026-11-18 15:30 in Kolkata (the reset zone); the week ends on Monday 2026-11-23 00:00 IST. */
	private static final Instant WEDNESDAY = Instant.parse("2026-11-18T10:00:00Z");
	private static final Instant NEXT_MONDAY = Instant.parse("2026-11-22T18:30:00Z");

	@BeforeEach
	void realTimeUnlessATestMovesIt() {
		clock.setTo(Instant.now());
	}

	// ---- fixtures ------------------------------------------------------------------------------

	private static String hex() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static String uniqueEmail() {
		return "user-" + hex() + "@example.com";
	}

	private User passwordUser(String email) {
		User user = userService.registerIfAbsent(email, "Test User", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	/** An account that signs in with Google only: no password. Created the way the account resolver creates one. */
	private User googleOnlyUser(String email, String sub) {
		return transactions.execute(status -> userService.createSocialUser(email, "Social User", Provider.GOOGLE, sub));
	}

	private void consume(User user, UsageType type, int units) {
		transactions.executeWithoutResult(status -> {
			usage.lock(user.getId(), type);
			for (int i = 0; i < units; i++) {
				usage.charge(user.getId(), type);
			}
		});
	}

	private int used(UUID userId, UsageType type) {
		UsageResponse response = usage.usageOf(userId);
		return response.usage().stream().filter(entry -> entry.usageType() == type).findFirst().orElseThrow().used();
	}

	private String bearer(User user) {
		return "Bearer " + jwtService.issueFor(user).value();
	}

	private MvcResult deleteWithPassword(User user, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/users/me/delete").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + password + "\"}")).andReturn();
	}

	private MvcResult postAs(User user, String path, String body) throws Exception {
		return mockMvc.perform(post(path).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	private MvcResult requestLink(User user) throws Exception {
		return postAs(user, "/api/v1/users/me/delete-request", "");
	}

	private MvcResult confirm(User user, String token) throws Exception {
		return postAs(user, "/api/v1/users/me/delete-confirm", "{\"token\":\"" + token + "\"}");
	}

	private static String code(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
	}

	private User deletedAndRegisteredAgain(User original, String emailOnReturn) throws Exception {
		assertThat(deleteWithPassword(original, PASSWORD).getResponse().getStatus()).isEqualTo(204);
		return userService.registerIfAbsent(emailOnReturn, "Returning", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
	}

	private int ledgerRows(String key) {
		return jdbc.queryForObject("SELECT count(*) FROM usage_ledger WHERE identity_key = ?", Integer.class, key);
	}

	// ---- the main scenario ---------------------------------------------------------------------

	@Test
	void deletingWithThePasswordKeepsTheCountsAndTheSameEmailReturnsAtItsLimit() throws Exception {
		String email = uniqueEmail();
		User user = passwordUser(email);
		consume(user, UsageType.REPOSITORY_ANALYSIS, ANALYSES);
		consume(user, UsageType.AI_QUERY, QUESTIONS);
		String oldToken = bearer(user);

		MvcResult deleted = deleteWithPassword(user, PASSWORD);

		assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
		assertThat(deleted.getResponse().getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).startsWith("refresh_token=;").contains("Max-Age=0"));
		assertThat(userService.findById(user.getId())).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_usage WHERE user_id = ?", Integer.class, user.getId())).isZero();
		assertThat(mail.lastTo(email).kind()).isEqualTo("ACCOUNT_DELETED");

		User returned = userService.registerIfAbsent(email, "Returning", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		assertThat(used(returned.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(ANALYSES);
		assertThat(used(returned.getId(), UsageType.AI_QUERY)).isEqualTo(QUESTIONS);
		MvcResult refused = postAs(returned, "/api/v1/analyses", "{\"repositoryUrl\":\"https://github.com/example/anything\"}");
		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(refused)).isEqualTo("USAGE_LIMIT_EXCEEDED");
		String usage = mockMvc.perform(get("/api/v1/usage").header("Authorization", bearer(returned))).andReturn().getResponse().getContentAsString();
		List<Boolean> reached = JsonPath.read(usage, "$.usage[*].limitReached");
		assertThat(reached).containsOnly(true);

		// the deleted account's access token stops working at once, and a second delete is a 401, not a 404 or a 500
		assertThat(mockMvc.perform(get("/api/v1/users/me").header("Authorization", oldToken)).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(post("/api/v1/users/me/delete").header("Authorization", oldToken).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"" + PASSWORD + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	// ---- the weekly Ask AI allowance (spec/weekly-ask-ai-limit) ------------------------------------------

	@Test
	void anExhaustedWeeklyAllowanceSurvivesDeletionInTheSameWeekAndIsZeroAfterTheNextMonday() throws Exception {
		clock.setTo(WEDNESDAY);
		String sameWeekEmail = uniqueEmail();
		User sameWeek = passwordUser(sameWeekEmail);
		consume(sameWeek, UsageType.AI_QUERY, QUESTIONS);
		consume(sameWeek, UsageType.REPOSITORY_ANALYSIS, ANALYSES);
		String nextWeekEmail = uniqueEmail();
		User nextWeek = passwordUser(nextWeekEmail);
		consume(nextWeek, UsageType.AI_QUERY, QUESTIONS);
		consume(nextWeek, UsageType.REPOSITORY_ANALYSIS, ANALYSES);
		assertThat(deleteWithPassword(sameWeek, PASSWORD).getResponse().getStatus()).isEqualTo(204);
		assertThat(deleteWithPassword(nextWeek, PASSWORD).getResponse().getStatus()).isEqualTo(204);

		clock.setTo(NEXT_MONDAY.minusSeconds(1)); // Sunday 23:59:59, still the same week
		User stillThisWeek = userService.registerIfAbsent(sameWeekEmail, "Returning", passwordEncoder.encode(PASSWORD), clock.instant()).orElseThrow();
		assertThat(used(stillThisWeek.getId(), UsageType.AI_QUERY)).isEqualTo(QUESTIONS); // still at the limit
		assertThat(used(stillThisWeek.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(ANALYSES);

		clock.setTo(NEXT_MONDAY); // Monday 00:00 sharp
		User afterTheReset = userService.registerIfAbsent(nextWeekEmail, "Returning", passwordEncoder.encode(PASSWORD), clock.instant()).orElseThrow();
		assertThat(used(afterTheReset.getId(), UsageType.AI_QUERY)).isZero(); // the old week restores nothing
		assertThat(used(afterTheReset.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(ANALYSES); // a lifetime count is unchanged
	}

	@Test
	void theLedgerStoresTheWindowOfAWeeklyCountAndNoneForALifetimeCount() throws Exception {
		clock.setTo(WEDNESDAY);
		String email = uniqueEmail();
		User user = passwordUser(email);
		consume(user, UsageType.AI_QUERY, 2);
		consume(user, UsageType.REPOSITORY_ANALYSIS, 1);

		deletedAndRegisteredAgain(user, email);

		assertThat(jdbc.queryForObject("SELECT window_start FROM usage_ledger WHERE identity_key = ? AND usage_type = 'AI_QUERY'",
				java.sql.Timestamp.class, ledger.emailKey(email)).toInstant()).isEqualTo(Instant.parse("2026-11-15T18:30:00Z"));
		assertThat(jdbc.queryForObject("SELECT window_start FROM usage_ledger WHERE identity_key = ? AND usage_type = 'REPOSITORY_ANALYSIS'",
				java.sql.Timestamp.class, ledger.emailKey(email))).isNull();
	}

	@Test
	void aLedgerEntryFromBeforeTheChangeRestoresNothingForAskAiAndStillRestoresAnalyses() throws Exception {
		String email = uniqueEmail();
		jdbc.update("INSERT INTO usage_ledger (id, identity_key, usage_type, used_count, updated_at) VALUES (?, ?, 'AI_QUERY', 20, now())",
				UUID.randomUUID(), ledger.emailKey(email)); // a lifetime count of 20, no window
		jdbc.update("INSERT INTO usage_ledger (id, identity_key, usage_type, used_count, updated_at) VALUES (?, ?, 'REPOSITORY_ANALYSIS', 2, now())",
				UUID.randomUUID(), ledger.emailKey(email));

		User returned = userService.registerIfAbsent(email, "Returning", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();

		assertThat(used(returned.getId(), UsageType.AI_QUERY)).isZero(); // nobody inherits a lifetime count of 20
		assertThat(used(returned.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(2);
	}

	@Test
	void aNewerWeeksCountReplacesAnOlderLedgerEntry() throws Exception {
		clock.setTo(WEDNESDAY);
		String email = uniqueEmail();
		User first = passwordUser(email);
		consume(first, UsageType.AI_QUERY, QUESTIONS); // the week of 16 Nov: 4
		assertThat(deleteWithPassword(first, PASSWORD).getResponse().getStatus()).isEqualTo(204);
		clock.setTo(NEXT_MONDAY.plus(java.time.Duration.ofDays(1))); // the week of 23 Nov
		User second = userService.registerIfAbsent(email, "Returning", passwordEncoder.encode(PASSWORD), clock.instant()).orElseThrow();
		second.markEmailVerified();
		second = userService.save(second);
		assertThat(used(second.getId(), UsageType.AI_QUERY)).isZero();
		consume(second, UsageType.AI_QUERY, 2);
		assertThat(deleteWithPassword(second, PASSWORD).getResponse().getStatus()).isEqualTo(204);

		User third = userService.registerIfAbsent(email, "Returning", passwordEncoder.encode(PASSWORD), clock.instant()).orElseThrow();

		assertThat(used(third.getId(), UsageType.AI_QUERY)).isEqualTo(2); // this week's 2, replacing last week's 4
		assertThat(jdbc.queryForObject("SELECT used_count FROM usage_ledger WHERE identity_key = ? AND usage_type = 'AI_QUERY'", Integer.class,
				ledger.emailKey(email))).isEqualTo(2);
	}

	@Test
	void theLedgerHoldsOnlyKeyedHashesAndCountsAndNoZeroRows() throws Exception {
		String email = uniqueEmail();
		User user = passwordUser(email);
		consume(user, UsageType.REPOSITORY_ANALYSIS, 2); // AI_QUERY is never used

		deletedAndRegisteredAgain(user, email);

		List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM usage_ledger WHERE identity_key = ?", ledger.emailKey(email));
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst()).containsEntry("usage_type", "REPOSITORY_ANALYSIS").containsEntry("used_count", 2);
		assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'usage_ledger'", String.class))
				.containsExactlyInAnyOrder("id", "identity_key", "usage_type", "used_count", "window_start", "updated_at");
		assertThat(jdbc.queryForList("SELECT identity_key FROM usage_ledger", String.class)).allMatch(key -> key.matches("[0-9a-f]{64}"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_ledger WHERE used_count <= 0", Integer.class)).isZero();
	}

	@Test
	void aNeverUsedAccountReturnsWithZeroAndLeavesNothingInTheLedger() throws Exception {
		String email = uniqueEmail();
		User user = passwordUser(email);

		User returned = deletedAndRegisteredAgain(user, email);

		assertThat(ledgerRows(ledger.emailKey(email))).isZero();
		assertThat(used(returned.getId(), UsageType.REPOSITORY_ANALYSIS)).isZero();
		assertThat(used(returned.getId(), UsageType.AI_QUERY)).isZero();
	}

	// ---- email variants ------------------------------------------------------------------------

	@Test
	void plusDotAndCaseVariantsOfAGmailAddressReturnToTheSameCounts() throws Exception {
		String id = hex();
		User user = passwordUser("first.last" + id + "@gmail.com");
		consume(user, UsageType.REPOSITORY_ANALYSIS, 2);
		consume(user, UsageType.AI_QUERY, 3);

		User returned = deletedAndRegisteredAgain(user, "FirstLast" + id + "+promo@GoogleMail.com");

		assertThat(used(returned.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(2);
		assertThat(used(returned.getId(), UsageType.AI_QUERY)).isEqualTo(3);
	}

	@Test
	void aPlusTagAndTheCaseOfAnyOtherDomainAreIgnoredButDotsAreNot() throws Exception {
		String id = hex();
		User user = passwordUser("a.b" + id + "@example.com");
		consume(user, UsageType.REPOSITORY_ANALYSIS, 2);
		assertThat(deleteWithPassword(user, PASSWORD).getResponse().getStatus()).isEqualTo(204);

		User dotsDiffer = userService.registerIfAbsent("ab" + id + "@example.com", "Other", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		User sameMailbox = userService.registerIfAbsent("A.B" + id + "+news@Example.COM", "Same", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();

		assertThat(used(dotsDiffer.getId(), UsageType.REPOSITORY_ANALYSIS)).isZero();
		assertThat(used(sameMailbox.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(2);
	}

	// ---- linked identities ---------------------------------------------------------------------

	@Test
	void deleteThenAnotherEmailThenLinkingTheOldGoogleIdentityRestoresTheCounts() throws Exception {
		String sub = "google-" + hex();
		User user = passwordUser(uniqueEmail());
		userService.link(user, Provider.GOOGLE, sub, "old@example.com");
		consume(user, UsageType.REPOSITORY_ANALYSIS, 3);
		consume(user, UsageType.AI_QUERY, 2);
		assertThat(deleteWithPassword(user, PASSWORD).getResponse().getStatus()).isEqualTo(204);

		User other = userService.registerIfAbsent(uniqueEmail(), "Other Email", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		assertThat(used(other.getId(), UsageType.REPOSITORY_ANALYSIS)).isZero();
		var result = linking.link(other.getId(), new IdentityLinkService.ProviderIdentityDetails(Provider.GOOGLE, sub, "old@example.com", null));

		assertThat(result).isEqualTo(IdentityLinkService.LinkResult.LINKED);
		assertThat(used(other.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(3);
		assertThat(used(other.getId(), UsageType.AI_QUERY)).isEqualTo(2);
	}

	@Test
	void aGoogleOnlyAccountDeletesWithAnEmailedLinkAndGetsItsCountsBackOnTheNextSignIn() throws Exception {
		String email = uniqueEmail();
		String sub = "google-" + hex();
		User user = googleOnlyUser(email, sub);
		consume(user, UsageType.REPOSITORY_ANALYSIS, 3);
		consume(user, UsageType.AI_QUERY, 1);

		assertThat(requestLink(user).getResponse().getStatus()).isEqualTo(202);
		RecordingEmailService.Sent sent = mail.lastTo(email);
		assertThat(sent.kind()).isEqualTo("ACCOUNT_DELETE");
		assertThat(sent.link()).contains("/settings/delete-confirm?token=");
		MvcResult done = confirm(user, sent.token());

		assertThat(done.getResponse().getStatus()).isEqualTo(204);
		assertThat(userService.findById(user.getId())).isEmpty();
		assertThat(mail.lastTo(email).kind()).isEqualTo("ACCOUNT_DELETED");
		// the next sign in with Google creates the account again, through the same call the account resolver makes
		User back = googleOnlyUser(email, sub);
		assertThat(back.getId()).isNotEqualTo(user.getId());
		assertThat(used(back.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(3);
		assertThat(used(back.getId(), UsageType.AI_QUERY)).isEqualTo(1);
	}

	// ---- the rules ----------------------------------------------------------------------------

	@Test
	void restoringTakesTheMaximumPerTypeAndNeverLowersACount() throws Exception {
		String lowGoogle = "google-" + hex();
		String bigGoogle = "google-" + hex();
		String github = "github-" + hex();
		User low = passwordUser(uniqueEmail());
		userService.link(low, Provider.GOOGLE, lowGoogle, null);
		consume(low, UsageType.REPOSITORY_ANALYSIS, 1);
		User big = passwordUser(uniqueEmail());
		userService.link(big, Provider.GOOGLE, bigGoogle, null);
		consume(big, UsageType.REPOSITORY_ANALYSIS, 2);
		User ai = passwordUser(uniqueEmail());
		userService.link(ai, Provider.GITHUB, github, null);
		consume(ai, UsageType.REPOSITORY_ANALYSIS, 1);
		consume(ai, UsageType.AI_QUERY, 3);
		for (User gone : List.of(low, big, ai)) {
			assertThat(deleteWithPassword(gone, PASSWORD).getResponse().getStatus()).isEqualTo(204);
		}

		// an account that has used more than the ledger remembers is never lowered
		User busy = passwordUser(uniqueEmail());
		consume(busy, UsageType.REPOSITORY_ANALYSIS, 3);
		linking.link(busy.getId(), new IdentityLinkService.ProviderIdentityDetails(Provider.GOOGLE, lowGoogle, null, null));
		assertThat(used(busy.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(3);

		// several identities: the maximum per type
		User fresh = passwordUser(uniqueEmail());
		linking.link(fresh.getId(), new IdentityLinkService.ProviderIdentityDetails(Provider.GOOGLE, bigGoogle, null, null));
		linking.link(fresh.getId(), new IdentityLinkService.ProviderIdentityDetails(Provider.GITHUB, github, null, null));
		assertThat(used(fresh.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(2); // max(2, 1)
		assertThat(used(fresh.getId(), UsageType.AI_QUERY)).isEqualTo(3);
	}

	@Test
	void aPremiumUserReturnsAsFreeWithTheirCounts() throws Exception {
		String email = uniqueEmail();
		User user = passwordUser(email);
		user.changePlan(Plan.PREMIUM, Instant.now().plus(Duration.ofDays(30)));
		userService.save(user);
		consume(user, UsageType.REPOSITORY_ANALYSIS, 5); // above the free limit of 3: premium is counted, never blocked

		User returned = deletedAndRegisteredAgain(user, email);

		assertThat(userService.profile(returned.getId()).orElseThrow().plan()).isEqualTo(Plan.FREE);
		assertThat(used(returned.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(5);
		assertThat(usage.usageOf(returned.getId()).usage().getFirst().limitReached()).isTrue();
	}

	@Test
	void aWrongPasswordIsRefusedCountsTowardsTheLockoutAndDeletesNothing() throws Exception {
		User user = passwordUser(uniqueEmail());
		consume(user, UsageType.REPOSITORY_ANALYSIS, 1);

		MvcResult wrong = deleteWithPassword(user, "not the password");

		assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
		assertThat(code(wrong)).isEqualTo("INVALID_CREDENTIALS");
		assertThat(wrong.getResponse().getContentAsString()).doesNotContain("not the password");
		assertThat(users.findById(user.getId()).orElseThrow().getFailedLoginAttempts()).isEqualTo(1);
		for (int attempt = 1; attempt < security.lockout().maxFailedAttempts(); attempt++) {
			deleteWithPassword(user, "still wrong");
		}
		assertThat(users.findById(user.getId()).orElseThrow().isLocked(Instant.now())).isTrue();
		assertThat(deleteWithPassword(user, PASSWORD).getResponse().getStatus()).isEqualTo(401); // locked: the right one is not even checked
		assertThat(userService.findById(user.getId())).isPresent();
		assertThat(ledgerRows(ledger.emailKey(user.getEmail()))).isZero(); // nothing was recorded for a refused deletion
	}

	@Test
	void theWrongFlowForTheAccountTypeIsAMismatch() throws Exception {
		User withPassword = passwordUser(uniqueEmail());
		User googleOnly = googleOnlyUser(uniqueEmail(), "google-" + hex());

		MvcResult needsPassword = requestLink(withPassword);
		MvcResult noPassword = deleteWithPassword(googleOnly, PASSWORD);
		MvcResult confirmForPasswordAccount = confirm(withPassword, "x".repeat(40));

		assertThat(needsPassword.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(needsPassword)).isEqualTo("DELETE_METHOD_MISMATCH");
		assertThat(noPassword.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(noPassword)).isEqualTo("DELETE_METHOD_MISMATCH");
		assertThat(confirmForPasswordAccount.getResponse().getStatus()).isEqualTo(400);
		assertThat(userService.findById(withPassword.getId())).isPresent();
		assertThat(userService.findById(googleOnly.getId())).isPresent();
		assertThat(mail.sentTo(withPassword.getEmail())).noneMatch(sent -> sent.kind().equals("ACCOUNT_DELETE"));
	}

	@Test
	void theLastEnabledAdministratorCannotDeleteThemselves() throws Exception {
		jdbc.update("UPDATE users SET enabled = false WHERE role = 'ADMIN'");
		User admin = userService.save(User.administrator(uniqueEmail(), "Only Admin", passwordEncoder.encode(PASSWORD), Instant.now()));

		MvcResult refused = deleteWithPassword(admin, PASSWORD);

		assertThat(refused.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(refused)).isEqualTo("LAST_ADMIN");
		assertThat(userService.findById(admin.getId())).isPresent();

		User second = userService.save(User.administrator(uniqueEmail(), "Second Admin", passwordEncoder.encode(PASSWORD), Instant.now()));
		assertThat(deleteWithPassword(admin, PASSWORD).getResponse().getStatus()).isEqualTo(204);
		assertThat(deleteWithPassword(second, PASSWORD).getResponse().getStatus()).isEqualTo(409); // now the last one
	}

	@Test
	void aConfirmationTokenIsSingleUseExpiringAndOnlyGoodForItsOwner() throws Exception {
		User owner = googleOnlyUser(uniqueEmail(), "google-" + hex());
		User other = googleOnlyUser(uniqueEmail(), "google-" + hex());
		requestLink(owner);
		String ownersToken = mail.lastTo(owner.getEmail()).token();

		// another user's token: refused, and it is not used up
		MvcResult stolen = confirm(other, ownersToken);
		assertThat(stolen.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(stolen)).isEqualTo("INVALID_OR_EXPIRED_TOKEN");
		assertThat(userService.findById(owner.getId())).isPresent();
		assertThat(userService.findById(other.getId())).isPresent();

		// a newer request voids the older link
		requestLink(owner);
		assertThat(confirm(owner, ownersToken).getResponse().getStatus()).isEqualTo(400);

		// an expired one
		String newest = mail.lastTo(owner.getEmail()).token();
		jdbc.update("UPDATE one_time_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = ? AND purpose = 'ACCOUNT_DELETE'", owner.getId());
		assertThat(confirm(owner, newest).getResponse().getStatus()).isEqualTo(400);

		// unknown
		assertThat(confirm(owner, "unknown-" + hex()).getResponse().getStatus()).isEqualTo(400);

		// a fresh one works exactly once; the second use finds no account at all (401)
		requestLink(owner);
		String fresh = mail.lastTo(owner.getEmail()).token();
		assertThat(confirm(owner, fresh).getResponse().getStatus()).isEqualTo(204);
		assertThat(confirm(owner, fresh).getResponse().getStatus()).isEqualTo(401);
		assertThat(userService.findById(owner.getId())).isEmpty();
		assertThat(userService.findById(other.getId())).isPresent();
	}

	@Test
	void theConfirmationLinkLastsFifteenMinutes() throws Exception {
		User owner = googleOnlyUser(uniqueEmail(), "google-" + hex());
		requestLink(owner);

		Instant expires = jdbc.queryForObject("SELECT expires_at FROM one_time_tokens WHERE user_id = ? AND purpose = 'ACCOUNT_DELETE'",
				java.sql.Timestamp.class, owner.getId()).toInstant();

		assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15).plusSeconds(5));
	}

	@Test
	void deletingRemovesTheSessionsTokensAndIdentitiesOfTheAccount() throws Exception {
		String email = uniqueEmail();
		User user = passwordUser(email);
		userService.link(user, Provider.GITHUB, "gh-" + hex(), email);
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
		mockMvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}")).andReturn();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, user.getId())).isPositive();

		assertThat(deleteWithPassword(user, PASSWORD).getResponse().getStatus()).isEqualTo(204);

		for (String table : List.of("refresh_tokens", "one_time_tokens", "oauth_link_intents", "linked_identities", "user_usage", "analyses")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, user.getId()))
					.as(table).isZero();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, user.getId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_graph_purges", Integer.class)).isZero(); // Neo4j dropped the (empty) graphs
	}

	// ---- nothing reveals the ledger ------------------------------------------------------------

	@Test
	void registrationAnswersTheSameWhetherOrNotTheLedgerKnowsTheAddress() throws Exception {
		String remembered = uniqueEmail();
		User user = passwordUser(remembered);
		consume(user, UsageType.REPOSITORY_ANALYSIS, 2);
		assertThat(deleteWithPassword(user, PASSWORD).getResponse().getStatus()).isEqualTo(204);

		MvcResult withMatch = register(remembered);
		MvcResult withoutMatch = register(uniqueEmail());

		assertThat(withMatch.getResponse().getStatus()).isEqualTo(withoutMatch.getResponse().getStatus()).isEqualTo(202);
		assertThat(withMatch.getResponse().getContentAsString()).isEqualTo(withoutMatch.getResponse().getContentAsString());
		assertThat(withMatch.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(withoutMatch.getResponse().getHeaderNames());
		assertThat(withMatch.getResponse().getContentType()).isEqualTo(withoutMatch.getResponse().getContentType());
		// the restored account is silent: its only email is the ordinary verification link
		assertThat(mail.sentTo(remembered).stream().filter(sent -> !sent.kind().equals("ACCOUNT_DELETED")))
				.extracting(RecordingEmailService.Sent::kind).containsExactly("VERIFY");
		User registered = userService.findByEmail(remembered).orElseThrow();
		assertThat(used(registered.getId(), UsageType.REPOSITORY_ANALYSIS)).isEqualTo(2);
	}

	@Test
	void loginAnswersTheSameWhetherOrNotTheLedgerKnowsTheAddress() throws Exception {
		String remembered = uniqueEmail();
		User user = passwordUser(remembered);
		consume(user, UsageType.AI_QUERY, 1);
		assertThat(deleteWithPassword(user, PASSWORD).getResponse().getStatus()).isEqualTo(204);

		MvcResult remembered401 = login(remembered);
		MvcResult unknown401 = login(uniqueEmail());

		assertThat(remembered401.getResponse().getStatus()).isEqualTo(unknown401.getResponse().getStatus()).isEqualTo(401);
		assertThat(remembered401.getResponse().getContentAsString()).isEqualTo(unknown401.getResponse().getContentAsString());
	}

	private MvcResult register(String email) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Returning\"}")).andReturn();
	}

	private MvcResult login(String email) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
	}
}
