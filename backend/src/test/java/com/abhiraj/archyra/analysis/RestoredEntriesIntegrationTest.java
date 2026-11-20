package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.usage.UsageLedgerService;
import com.abhiraj.archyra.usage.UsageService;
import com.abhiraj.archyra.usage.UsageType;
import com.abhiraj.archyra.user.AccountDeletionService;
import com.abhiraj.archyra.user.IdentityLinkService;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.Provider;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/**
 * Restoring the repositories of a deleted account as {@code NOT_ANALYZED} entries ({@code spec/account-deletion}, its
 * amendment): the retained links, the restore on registration, sign-in and linking, deleting an entry (a free user's
 * credit comes back once), analysing one (the credit already spent is used, nothing is charged twice) and the cap. Real
 * PostgreSQL and Neo4j; the pipeline is stubbed, so the runs themselves are in
 * {@code RestoredEntriesPipelineIntegrationTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class RestoredEntriesIntegrationTest extends UsageIntegrationSupport {

	private static final String PASSWORD = "irrelevant"; // what UsageFixtures hashes

	@Autowired
	TransactionTemplate transactions;
	@Autowired
	UsageLedgerService ledger;
	@Autowired
	UsageService usageService;
	@Autowired
	IdentityLinkService linking;
	@Autowired
	AccountDeletionService deletion;

	@BeforeEach
	void aFreshPipelineStub() {
		reset(pipeline);
	}

	// ---- fixtures ------------------------------------------------------------------------------

	private static String hex() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private User account(String email) {
		User user = userService.registerIfAbsent(email, "Restore Test", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	/** Starts and completes {@code n} analyses of fresh URLs (the stubbed pipeline leaves them queued, so each is finished by hand). */
	private List<String> spend(User user, int n) throws Exception {
		List<String> urls = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			urls.add(freshUrl());
		}
		spend(user, urls);
		return urls;
	}

	private void spend(User user, List<String> urls) throws Exception {
		for (String url : urls) {
			finish(createOk(user, url), AnalysisStatus.COMPLETED);
		}
	}

	private void deleteAccountWithPassword(User user) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/users/me/delete").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}")).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(204);
	}

	/** An account without a password (social sign-in only) is deleted through the service, as the confirmed link flow does. */
	private void deleteAccountDirectly(User user) {
		AccountDeletionService.Deleted deleted = transactions.execute(status -> deletion.delete(user.getId(), "127.0.0.1"));
		deletion.afterDeletion(deleted);
	}

	private User googleAccount(String email, String sub) {
		return transactions.execute(status -> userService.createSocialUser(email, "Social", Provider.GOOGLE, sub));
	}

	private User again(String email) {
		return account(email);
	}

	private List<String> entryUrls(User user) {
		return jdbc.queryForList("select repository_url from analyses where user_id = ? and status = 'NOT_ANALYZED' order by repository_url",
				String.class, user.getId());
	}

	private int entryCount(User user) {
		return entryUrls(user).size();
	}

	private UUID entryId(User user, String url) {
		return jdbc.queryForObject("select id from analyses where user_id = ? and repository_url = ? and status = 'NOT_ANALYZED'",
				UUID.class, user.getId(), url);
	}

	private MvcResult postAs(User user, String path, String json) throws Exception {
		return mockMvc.perform(post(path).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private MvcResult getAs(User user, String path) throws Exception {
		return mockMvc.perform(get(path).header("Authorization", bearer(user))).andReturn();
	}

	private MvcResult analyze(User user, UUID id) throws Exception {
		return postAs(user, "/api/v1/analyses/" + id + "/analyze", "");
	}

	private int count(String table, UUID analysisId) {
		return jdbc.queryForObject("select count(*) from " + table + " where analysis_id = ?", Integer.class, analysisId);
	}

	private int ledgerRepositoryRows(String key) {
		return jdbc.queryForObject("select count(*) from usage_ledger_repository where identity_key = ?", Integer.class, key);
	}

	private int ledgerCount(String key, UsageType type) {
		return jdbc.query("select used_count from usage_ledger where identity_key = ? and usage_type = ?",
				rs -> rs.next() ? rs.getInt(1) : -1, key, type.name());
	}

	// ---- restore on registration ---------------------------------------------------------------

	@Test
	void anAccountAtItsLimitReturnsWithTheSameRepositoriesAsNotAnalysedEntriesAndNothingElse() throws Exception {
		String email = "restore-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, limit());
		assertThat(used(user)).isEqualTo(limit());
		deleteAccountWithPassword(user);

		User returned = again(email);

		assertThat(entryUrls(returned)).containsExactlyInAnyOrderElementsOf(urls);
		assertThat(used(returned)).isEqualTo(limit());
		assertThat(analysisRows(returned)).isEqualTo(limit());
		assertThat(chargedAndNotRefunded(returned)).isEqualTo(limit());
		for (UUID id : jdbc.queryForList("select id from analyses where user_id = ?", UUID.class, returned.getId())) {
			assertThat(id).isNotIn(jdbc.queryForList("select id from analyses where user_id = ?", UUID.class, user.getId()));
			assertThat(count("analysis_metadata", id)).isZero();
			assertThat(count("ai_index", id)).isZero();
			assertThat(count("code_chunks", id)).isZero();
			assertThat(count("conversations", id)).isZero();
		}
		assertThat(jdbc.queryForObject("select count(*) from analyses where user_id = ? and progress <> 0 and status = 'NOT_ANALYZED'",
				Integer.class, returned.getId())).isZero();

		// the API shows them: list and status say NOT_ANALYZED, with the URL
		MvcResult list = getAs(returned, "/api/v1/analyses?size=50");
		List<String> statuses = JsonPath.read(body(list), "$.items[*].status");
		assertThat(statuses).hasSize(limit()).containsOnly("NOT_ANALYZED");
		UUID first = entryId(returned, urls.get(0));
		MvcResult status = getAs(returned, "/api/v1/analyses/" + first);
		assertThat(status.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) JsonPath.read(body(status), "$.status")).isEqualTo("NOT_ANALYZED");
		assertThat((Integer) JsonPath.read(body(status), "$.progress")).isZero();
	}

	@Test
	void plusTagAndDotGmailVariantsRestoreTheSameEntries() throws Exception {
		String local = "dot" + hex().substring(0, 10);
		User user = account(local.substring(0, 4) + "." + local.substring(4) + "+first@gmail.com");
		List<String> urls = spend(user, 2);
		deleteAccountWithPassword(user);

		User returned = again(local + "@googlemail.com");

		assertThat(entryUrls(returned)).containsExactlyInAnyOrderElementsOf(urls);
		assertThat(used(returned)).isEqualTo(2);
	}

	@Test
	void aGoogleIdentityRestoresTheEntriesOnItsNextSignInAndTheTwoKeysDoNotDoubleThem() throws Exception {
		String sub = "google-" + hex();
		User user = googleAccount("g-" + hex() + "@example.com", sub);
		List<String> urls = spend(user, 2);
		deleteAccountDirectly(user);

		// the same person signs in again with another address: the identity key alone matches
		User viaIdentity = googleAccount("g2-" + hex() + "@example.com", sub);
		assertThat(entryUrls(viaIdentity)).containsExactlyInAnyOrderElementsOf(urls);
		assertThat(used(viaIdentity)).isEqualTo(2);

	}

	@Test
	void anEmailAndAnIdentityThatHoldTheSameRepositoriesDoNotDoubleThem() throws Exception {
		String sub = "google-" + hex();
		String email = "both-" + hex() + "@example.com";
		User user = googleAccount(email, sub); // the email key and the Google key will both hold these links
		List<String> urls = spend(user, 2);
		deleteAccountDirectly(user);

		User returned = googleAccount(email, sub);

		assertThat(analysisRows(returned)).isEqualTo(2);
		assertThat(entryUrls(returned)).containsExactlyInAnyOrderElementsOf(urls);
		assertThat(used(returned)).isEqualTo(2);
	}

	@Test
	void aGitHubIdentityRestoresTheEntries() throws Exception {
		String id = "github-" + hex();
		User user = transactions.execute(status -> userService.createSocialUser("gh-" + hex() + "@example.com", "Hub", Provider.GITHUB, id));
		List<String> urls = spend(user, 3);
		deleteAccountDirectly(user);

		User returned = transactions.execute(status -> userService.createSocialUser("gh2-" + hex() + "@example.com", "Hub", Provider.GITHUB, id));

		assertThat(entryUrls(returned)).containsExactlyInAnyOrderElementsOf(urls);
		assertThat(used(returned)).isEqualTo(3);
	}

	@Test
	void oneRepositoryPaidForTwiceComesBackTwice() throws Exception {
		String email = "twice-" + hex() + "@example.com";
		User user = account(email);
		String url = freshUrl();
		spend(user, List.of(url, url)); // the first completes, so the second is a new analysis (and a second credit)
		assertThat(used(user)).isEqualTo(2);
		deleteAccountWithPassword(user);

		User returned = again(email);

		assertThat(entryUrls(returned)).containsExactly(url, url);
		assertThat(used(returned)).isEqualTo(2);
	}

	// ---- linking later -------------------------------------------------------------------------

	@Test
	void linkingTheOldIdentityToANewAccountCreatesOnlyTheMissingEntries() throws Exception {
		String sub = "google-" + hex();
		User old = googleAccount("old-" + hex() + "@example.com", sub);
		List<String> oldUrls = spend(old, 3);
		deleteAccountDirectly(old);

		User fresh = account("fresh-" + hex() + "@example.com");
		String own = freshUrl();
		spend(fresh, List.of(own));
		assertThat(used(fresh)).isEqualTo(1);

		assertThat(linking.link(fresh.getId(), new IdentityLinkService.ProviderIdentityDetails(Provider.GOOGLE, sub, null, null)))
				.isEqualTo(IdentityLinkService.LinkResult.LINKED);

		// the count became the larger one (3) and the account holds its own repository plus the two newest restored ones
		assertThat(used(fresh)).isEqualTo(3);
		assertThat(analysisRows(fresh)).isEqualTo(3);
		assertThat(entryCount(fresh)).isEqualTo(2);
		assertThat(entryUrls(fresh)).isSubsetOf(oldUrls);
		assertThat(chargedAndNotRefunded(fresh)).isEqualTo(3);
	}

	@Test
	void linkingAnIdentityWhoseRepositoriesTheAccountAlreadyHasDuplicatesNothing() throws Exception {
		String sub = "google-" + hex();
		String email = "dup-" + hex() + "@example.com";
		User old = googleAccount(email, sub);
		List<String> urls = spend(old, 2);
		deleteAccountDirectly(old);

		User returned = account(email); // the email key restores both entries
		assertThat(entryUrls(returned)).containsExactlyInAnyOrderElementsOf(urls);
		assertThat(linking.link(returned.getId(), new IdentityLinkService.ProviderIdentityDetails(Provider.GOOGLE, sub, null, null)))
				.isEqualTo(IdentityLinkService.LinkResult.LINKED);

		assertThat(analysisRows(returned)).isEqualTo(2);
		assertThat(entryUrls(returned)).containsExactlyInAnyOrderElementsOf(urls);
		assertThat(used(returned)).isEqualTo(2);
	}

	// ---- deleting an entry ---------------------------------------------------------------------

	@Test
	void deletingAnEntryReturnsExactlyOneCreditToAFreeUserNeverTwiceAndNeverBelowZero() throws Exception {
		String email = "del-" + hex() + "@example.com";
		User user = account(email);
		spend(user, 3);
		deleteAccountWithPassword(user);
		User returned = again(email);
		UUID entry = jdbc.queryForObject("select id from analyses where user_id = ? limit 1", UUID.class, returned.getId());

		assertThat(deleteAnalysis(returned, entry).getResponse().getStatus()).isEqualTo(204);

		assertThat(used(returned)).isEqualTo(2);
		assertThat(analysisRows(returned)).isEqualTo(2);
		MvcResult again = deleteAnalysis(returned, entry);
		assertThat(again.getResponse().getStatus()).isEqualTo(404);
		assertThat(used(returned)).isEqualTo(2);
		// all of them: the count ends at zero, never below
		for (UUID id : jdbc.queryForList("select id from analyses where user_id = ?", UUID.class, returned.getId())) {
			assertThat(deleteAnalysis(returned, id).getResponse().getStatus()).isEqualTo(204);
		}
		assertThat(used(returned)).isZero();
		assertThat(usage(returned).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void deletingAnEntryOfAPremiumUserReturnsNothing() throws Exception {
		String email = "prem-" + hex() + "@example.com";
		User user = account(email);
		spend(user, 2);
		deleteAccountWithPassword(user);
		User returned = again(email);
		returned.changePlan(Plan.PREMIUM, Instant.now().plusSeconds(86_400));
		userService.save(returned);
		UUID entry = jdbc.queryForObject("select id from analyses where user_id = ? limit 1", UUID.class, returned.getId());

		assertThat(deleteAnalysis(returned, entry).getResponse().getStatus()).isEqualTo(204);

		assertThat(used(returned)).isEqualTo(2); // still counted, as for every premium deletion
		assertThat(analysisRows(returned)).isEqualTo(1);
	}

	// ---- analysing an entry --------------------------------------------------------------------

	@Test
	void analysingAnEntryChargesNothingStartsOneRunAndWorksAtTheLimit() throws Exception {
		String email = "run-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, limit());
		deleteAccountWithPassword(user);
		User returned = again(email);
		assertThat(used(returned)).isEqualTo(limit()); // at the limit: analysing needs no headroom
		UUID entry = entryId(returned, urls.get(0));

		MvcResult started = analyze(returned, entry);

		assertThat(started.getResponse().getStatus()).isEqualTo(202);
		assertThat((String) JsonPath.read(body(started), "$.analysisId")).isEqualTo(entry.toString());
		assertThat((String) JsonPath.read(body(started), "$.status")).isEqualTo("QUEUED");
		assertThat(used(returned)).isEqualTo(limit());
		Analysis row = analyses.findById(entry).orElseThrow();
		assertThat(row.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
		assertThat(row.isQuotaCharged()).isTrue();
		assertThat(row.isQuotaRefunded()).isFalse();
		verify(pipeline, times(1)).run(entry);
		assertThat(analysisRows(returned)).isEqualTo(limit()); // the same row, not a new one

		MvcResult second = analyze(returned, entry);
		assertThat(second.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(second)).isEqualTo("ANALYSIS_NOT_RESTORABLE");
		verify(pipeline, times(1)).run(entry);
		assertThat(used(returned)).isEqualTo(limit());
	}

	@Test
	void aRunThatFailsAfterwardsReturnsTheCreditOnceAndTheRowEndsFailed() throws Exception {
		String email = "fail-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, 2);
		deleteAccountWithPassword(user);
		User returned = again(email);
		UUID entry = entryId(returned, urls.get(0));
		assertThat(analyze(returned, entry).getResponse().getStatus()).isEqualTo(202);

		registrar.failAndRefund(entry, "The repository could not be cloned.");

		assertThat(analyses.findById(entry).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(used(returned)).isEqualTo(1);
		assertThat(registrar.refundQuota(entry)).isFalse(); // exactly once
		assertThat(used(returned)).isEqualTo(1);
		// the FAILED row cannot be analysed this way and deleting it returns nothing more
		assertThat(code(analyze(returned, entry))).isEqualTo("ANALYSIS_NOT_RESTORABLE");
		assertThat(deleteAnalysis(returned, entry).getResponse().getStatus()).isEqualTo(204);
		assertThat(used(returned)).isEqualTo(1);
	}

	@Test
	void aFullQueuePutsTheEntryBackWithoutRefundOrFailure() throws Exception {
		String email = "busy-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, 2);
		deleteAccountWithPassword(user);
		User returned = again(email);
		UUID entry = entryId(returned, urls.get(0));
		doThrow(new TaskRejectedException("queue full")).when(pipeline).run(any());

		MvcResult busy = analyze(returned, entry);

		assertThat(busy.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(busy)).isEqualTo("SERVICE_BUSY");
		Analysis row = analyses.findById(entry).orElseThrow();
		assertThat(row.getStatus()).isEqualTo(AnalysisStatus.NOT_ANALYZED);
		assertThat(row.isQuotaCharged()).isTrue();
		assertThat(row.isQuotaRefunded()).isFalse();
		assertThat(used(returned)).isEqualTo(2);

		// nothing was lost: once the queue has room it can be analysed
		reset(pipeline);
		assertThat(analyze(returned, entry).getResponse().getStatus()).isEqualTo(202);
		verify(pipeline, times(1)).run(entry);
	}

	@Test
	void twoSimultaneousRequestsStartOneRunAndTheLoserGetsTheConflict() throws Exception {
		String email = "race-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, 2);
		deleteAccountWithPassword(user);
		User returned = again(email);
		UUID entry = entryId(returned, urls.get(0));

		ExecutorService threads = Executors.newFixedThreadPool(4);
		CyclicBarrier together = new CyclicBarrier(4);
		List<Integer> statuses = new ArrayList<>();
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Callable<Integer> call = () -> {
					together.await(30, TimeUnit.SECONDS);
					return analyze(returned, entry).getResponse().getStatus();
				};
				futures.add(threads.submit(call));
			}
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
		}
		finally {
			threads.shutdownNow();
		}

		assertThat(statuses.stream().filter(status -> status == 202)).hasSize(1);
		assertThat(statuses.stream().filter(status -> status == 409)).hasSize(3);
		verify(pipeline, times(1)).run(entry);
		assertThat(used(returned)).isEqualTo(2);
	}

	@Test
	void anotherUsersEntryIsNotFoundAndACompletedOrRunningAnalysisIsAConflict() throws Exception {
		String email = "own-" + hex() + "@example.com";
		User owner = account(email);
		List<String> urls = spend(owner, 1);
		deleteAccountWithPassword(owner);
		User returned = again(email);
		UUID entry = entryId(returned, urls.get(0));
		User stranger = freeUser();

		MvcResult foreign = analyze(stranger, entry);
		MvcResult unknown = analyze(stranger, UUID.randomUUID());
		assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
		assertThat(body(foreign)).isEqualTo(body(unknown));
		assertThat(analyses.findById(entry).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.NOT_ANALYZED);
		assertThat(deleteAnalysis(stranger, entry).getResponse().getStatus()).isEqualTo(404);

		UUID completed = createOk(returned, freshUrl()); // below the limit: the entry plus this one
		UUID running = createOk(returned, freshUrl());
		finish(completed, AnalysisStatus.COMPLETED);
		for (UUID id : List.of(completed, running)) {
			MvcResult conflict = analyze(returned, id);
			assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
			assertThat(code(conflict)).isEqualTo("ANALYSIS_NOT_RESTORABLE");
		}
		verify(pipeline, never()).run(entry);
	}

	@Test
	void reanalysingAnEntryIsRefusedInsteadOfChargingASecondCredit() throws Exception {
		String email = "rean-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, 1);
		deleteAccountWithPassword(user);
		User returned = again(email);
		UUID entry = entryId(returned, urls.get(0));

		MvcResult refused = postAs(returned, "/api/v1/analyses/" + entry + "/reanalyze", "");

		assertThat(refused.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(refused)).isEqualTo("ANALYSIS_NOT_COMPLETED");
		assertThat(used(returned)).isEqualTo(1);
		assertThat(analysisRows(returned)).isEqualTo(1);
	}

	// ---- the limit -----------------------------------------------------------------------------

	@Test
	void anAccountAtTheLimitWithEntriesCannotStartANewAnalysisUntilOneIsDeleted() throws Exception {
		String email = "cap-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, limit());
		deleteAccountWithPassword(user);
		User returned = again(email);

		MvcResult refused = create(returned, freshUrl());
		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(refused)).isEqualTo("USAGE_LIMIT_EXCEEDED");
		assertThat(analysisRows(returned)).isEqualTo(limit());

		assertThat(deleteAnalysis(returned, entryId(returned, urls.get(0))).getResponse().getStatus()).isEqualTo(204);
		assertThat(create(returned, freshUrl()).getResponse().getStatus()).isEqualTo(202);
		assertThat(used(returned)).isEqualTo(limit());
	}

	@Test
	void fewerLinksThanCreditsRestoreFewerEntriesAndTheRestStaySpent() throws Exception {
		String email = "few-" + hex() + "@example.com";
		User user = account(email);
		spend(user, 4);
		deleteAccountWithPassword(user);
		String key = ledger.emailKey(email);
		jdbc.update("delete from usage_ledger_repository where identity_key = ? and id in (select id from usage_ledger_repository "
				+ "where identity_key = ? order by created_at limit 3)", key, key); // an old ledger row has counts and no links

		User returned = again(email);

		assertThat(used(returned)).isEqualTo(4);
		assertThat(entryCount(returned)).isEqualTo(1);
	}

	@Test
	void moreLinksThanCreditsRestoreOnlyTheNewestOnes() throws Exception {
		String email = "many-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, 3);
		deleteAccountWithPassword(user);
		String key = ledger.emailKey(email);
		Instant farFuture = Instant.now().plusSeconds(3600);
		List<String> newest = List.of(freshUrl(), freshUrl());
		for (int i = 0; i < newest.size(); i++) {
			jdbc.update("insert into usage_ledger_repository (id, identity_key, repository_url, created_at) values (?, ?, ?, ?)",
					UUID.randomUUID(), key, newest.get(i), java.sql.Timestamp.from(farFuture.plusSeconds(i)));
		}
		assertThat(ledgerRepositoryRows(key)).isEqualTo(5); // five links for three credits

		User returned = again(email);

		assertThat(used(returned)).isEqualTo(3);
		assertThat(entryCount(returned)).isEqualTo(3);
		assertThat(entryUrls(returned)).containsAll(newest).doesNotContainAnyElementsOf(List.of(urls.get(0)));
	}

	// ---- the overwrite rule --------------------------------------------------------------------

	@Test
	void restoreThenDeleteAnEntryThenDeleteTheAccountAgainEndsWithTheLowerCountAndTheMatchingEntries() throws Exception {
		String email = "seq-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, 3);
		deleteAccountWithPassword(user);
		User returned = again(email);
		assertThat(entryCount(returned)).isEqualTo(3);
		String removed = urls.get(1);
		assertThat(deleteAnalysis(returned, entryId(returned, removed)).getResponse().getStatus()).isEqualTo(204);
		assertThat(used(returned)).isEqualTo(2);

		deleteAccountWithPassword(returned); // all the entries are NOT_ANALYZED: they do not block the deletion
		String key = ledger.emailKey(email);
		assertThat(ledgerCount(key, UsageType.REPOSITORY_ANALYSIS)).isEqualTo(2); // overwritten, not the old maximum of 3
		assertThat(ledgerRepositoryRows(key)).isEqualTo(2);

		User third = again(email);
		assertThat(used(third)).isEqualTo(2);
		assertThat(entryUrls(third)).containsExactlyInAnyOrder(urls.get(0), urls.get(2));
	}

	@Test
	void anAccountThatReturnedDeletedEveryEntryAndLeavesAgainLeavesNothingBehind() throws Exception {
		String email = "none-" + hex() + "@example.com";
		User user = account(email);
		spend(user, 2);
		deleteAccountWithPassword(user);
		User returned = again(email);
		for (UUID id : jdbc.queryForList("select id from analyses where user_id = ?", UUID.class, returned.getId())) {
			assertThat(deleteAnalysis(returned, id).getResponse().getStatus()).isEqualTo(204);
		}
		assertThat(used(returned)).isZero();

		deleteAccountWithPassword(returned);

		String key = ledger.emailKey(email);
		assertThat(ledgerCount(key, UsageType.REPOSITORY_ANALYSIS)).isEqualTo(-1); // the row is gone: the count is zero
		assertThat(ledgerRepositoryRows(key)).isZero();
		assertThat(used(again(email))).isZero();
	}

	@Test
	void deletingAnAccountOverwritesTheWeeklyAskAiCountToo() throws Exception {
		String email = "week-" + hex() + "@example.com";
		User user = account(email);
		transactions.executeWithoutResult(status -> {
			usageService.lock(user.getId(), UsageType.AI_QUERY);
			for (int i = 0; i < 5; i++) {
				usageService.charge(user.getId(), UsageType.AI_QUERY);
			}
		});
		deleteAccountWithPassword(user);
		String key = ledger.emailKey(email);
		assertThat(ledgerCount(key, UsageType.AI_QUERY)).isEqualTo(5);

		User returned = again(email);
		assertThat(askAiUsed(returned)).isEqualTo(5);
		// two questions fail and are refunded to the window they were charged in, as a real chat failure does
		java.time.Instant window = jdbc.queryForObject("select window_start from user_usage where user_id = ? and usage_type = 'AI_QUERY'",
				java.sql.Timestamp.class, returned.getId()).toInstant();
		transactions.executeWithoutResult(status -> {
			usageService.release(returned.getId(), UsageType.AI_QUERY, window);
			usageService.release(returned.getId(), UsageType.AI_QUERY, window);
		});
		assertThat(askAiUsed(returned)).isEqualTo(3);

		deleteAccountWithPassword(returned);

		assertThat(ledgerCount(key, UsageType.AI_QUERY)).isEqualTo(3); // the current state, not the older 5
		assertThat(askAiUsed(again(email))).isEqualTo(3);
	}

	private int askAiUsed(User user) {
		return jdbc.query("select used_count from user_usage where user_id = ? and usage_type = 'AI_QUERY'",
				rs -> rs.next() ? rs.getInt(1) : 0, user.getId());
	}

	@Test
	void theRestoreAndMergeStillTakeTheMaximumAndAnOldLedgerRowRestoresCountsOnly() throws Exception {
		String email = "old-" + hex() + "@example.com";
		// a row written before this change: a count and no repositories
		jdbc.update("insert into usage_ledger (id, identity_key, usage_type, used_count, updated_at) values (?, ?, 'REPOSITORY_ANALYSIS', 3, now())",
				UUID.randomUUID(), ledger.emailKey(email));

		User returned = again(email);

		assertThat(used(returned)).isEqualTo(3);
		assertThat(analysisRows(returned)).isZero();
	}

	// ---- what a NOT_ANALYZED entry is for everything else --------------------------------------

	@Test
	void everyResultEndpointAnswersTheNotCompletedErrorForAnEntryNeverAServerErrorOrARunningMessage() throws Exception {
		String email = "res-" + hex() + "@example.com";
		User user = account(email);
		List<String> urls = spend(user, 1);
		deleteAccountWithPassword(user);
		User returned = again(email);
		UUID entry = entryId(returned, urls.get(0));
		String base = "/api/v1/analyses/" + entry;
		int aiBefore = askAiUsed(returned);

		for (String path : List.of("/repository", "/files", "/graph", "/ai-index", "/conversations", "/graph?nodeType=CLASS")) {
			MvcResult result = getAs(returned, base + path);
			assertThat(result.getResponse().getStatus()).as(path).isEqualTo(409);
			assertThat(code(result)).as(path).isEqualTo("ANALYSIS_NOT_COMPLETED");
			assertThat((String) JsonPath.read(body(result), "$.message")).as(path).contains("not been analysed").doesNotContain("still running");
		}
		for (String path : List.of("/ai-index", "/conversations", "/chat")) {
			MvcResult result = postAs(returned, base + path, path.equals("/chat") ? "{\"message\":\"what is this\"}" : "{}");
			assertThat(result.getResponse().getStatus()).as(path).isEqualTo(409);
			assertThat(code(result)).as(path).isEqualTo("ANALYSIS_NOT_COMPLETED");
		}
		assertThat(askAiUsed(returned)).isEqualTo(aiBefore);
	}

	@Test
	void aRestartDoesNotTurnAnEntryIntoAFailedAnalysis() throws Exception {
		String email = "restart-" + hex() + "@example.com";
		User user = account(email);
		spend(user, 2);
		deleteAccountWithPassword(user);
		User returned = again(email);

		recovery.failInterruptedAnalyses();

		assertThat(entryCount(returned)).isEqualTo(2);
		assertThat(used(returned)).isEqualTo(2);
	}

	// ---- privacy -------------------------------------------------------------------------------

	@Test
	void registrationAnswersTheSameWithAndWithoutRetainedRepositoriesAndNoLogLineLinksAUrlToAnIdentity(CapturedOutput output) throws Exception {
		String remembered = "priv-" + hex() + "@example.com";
		User user = account(remembered);
		List<String> urls = spend(user, 2);
		deleteAccountWithPassword(user);

		MvcResult withMatch = register(remembered);
		MvcResult withoutMatch = register("priv-" + hex() + "@example.com");

		assertThat(withMatch.getResponse().getStatus()).isEqualTo(withoutMatch.getResponse().getStatus()).isEqualTo(202);
		assertThat(withMatch.getResponse().getContentAsString()).isEqualTo(withoutMatch.getResponse().getContentAsString());
		assertThat(withMatch.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(withoutMatch.getResponse().getHeaderNames());
		assertThat(withMatch.getResponse().getContentAsString()).doesNotContain("github.com");
		User registered = userService.findByEmail(remembered).orElseThrow();
		assertThat(entryUrls(registered)).containsExactlyInAnyOrderElementsOf(urls);

		String key = ledger.emailKey(remembered);
		for (String line : output.getAll().split("\\R")) {
			if (line.contains(user.getId().toString()) || line.contains(registered.getId().toString()) || line.contains(key)
					|| line.contains(remembered)) {
				assertThat(line).as("a log line naming a user, an email or a key must not name a repository").doesNotContain("github.com/");
			}
		}
		// the raw URLs are in the ledger table only, never beside anything else of the person
		assertThat(jdbc.queryForObject("select count(*) from usage_ledger_repository where repository_url like 'https://github.com/%'",
				Integer.class)).isPositive();
		assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'usage_ledger_repository'", String.class))
				.containsExactlyInAnyOrder("id", "identity_key", "repository_url", "created_at");
	}

	private MvcResult register(String email) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery\",\"name\":\"Returning\"}")).andReturn();
	}
}
