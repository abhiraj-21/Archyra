package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.usage.UsageService;
import com.abhiraj.archyra.usage.UsageType;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/** The free-tier allowance end to end: counting, blocking, duplicates, refunds, premium and the database rules. */
class UsageLimitIntegrationTest extends UsageIntegrationSupport {

	@Autowired
	UsageService usageService;
	@Autowired
	TransactionTemplate transactions;

	private int usedInResponse(User user, int index) throws Exception {
		return JsonPath.read(body(usage(user)), "$.usage[" + index + "].used");
	}

	// ---- counting and reading ------------------------------------------------------------------

	@Test
	void everyAnalysisIsCountedAndAnotherUsersUsageIsUnaffected() throws Exception {
		User user = freeUser();
		User other = freeUser();

		for (int expected = 1; expected <= 3; expected++) {
			createOk(user, freshUrl());
			String json = body(usage(user));
			assertThat((String) JsonPath.read(json, "$.plan")).isEqualTo("FREE");
			assertThat((String) JsonPath.read(json, "$.usage[0].usageType")).isEqualTo("REPOSITORY_ANALYSIS");
			assertThat((Integer) JsonPath.read(json, "$.usage[0].used")).isEqualTo(expected);
			assertThat((Integer) JsonPath.read(json, "$.usage[0].limit")).isEqualTo(limit());
			assertThat((Integer) JsonPath.read(json, "$.usage[0].remaining")).isEqualTo(limit() - expected);
			assertThat((Boolean) JsonPath.read(json, "$.usage[0].limitReached")).isEqualTo(expected >= limit());
		}

		assertThat(usedInResponse(other, 0)).isZero();
		assertThat(used(other)).isZero();
	}

	@Test
	void readingUsageNeverCreatesARow() throws Exception {
		User user = freeUser();

		MvcResult result = usage(user);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat((Integer) JsonPath.read(body(result), "$.usage[0].used")).isZero();
		assertThat(usageRows(user)).isZero();
	}

	@Test
	void everyUsageTypeIsListed() throws Exception {
		String json = body(usage(freeUser()));

		assertThat((Integer) JsonPath.read(json, "$.usage.length()")).isEqualTo(UsageType.values().length);
		assertThat((String) JsonPath.read(json, "$.usage[1].usageType")).isEqualTo("AI_QUERY");
		assertThat((Integer) JsonPath.read(json, "$.usage[1].used")).isZero();
	}

	@Test
	void usageNeedsAToken() throws Exception {
		assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/usage")).andReturn()
				.getResponse().getStatus()).isEqualTo(401);
	}

	// ---- blocking ------------------------------------------------------------------------------

	@Test
	void atTheLimitTheNextAnalysisIsRefusedAndNothingIsCreated() throws Exception {
		User user = freeUser();
		for (int i = 0; i < limit(); i++) {
			createOk(user, freshUrl());
		}

		MvcResult refused = create(user, freshUrl());

		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(refused)).isEqualTo("USAGE_LIMIT_EXCEEDED");
		assertThat((String) JsonPath.read(body(refused), "$.message"))
				.isEqualTo("You have used all " + limit() + " free repository analyses. Upgrade to Archyra Premium to continue.");
		assertThat(analysisRows(user)).isEqualTo(limit());
		assertThat(used(user)).isEqualTo(limit());
		verify(pipeline, times(limit())).run(any());

		String json = body(usage(user));
		assertThat((Integer) JsonPath.read(json, "$.usage[0].remaining")).isZero();
		assertThat((Boolean) JsonPath.read(json, "$.usage[0].limitReached")).isTrue();
	}

	@Test
	void deletingAnAnalysisDoesNotGiveTheUnitBack() throws Exception {
		User user = freeUser();
		UUID first = createOk(user, freshUrl());
		for (int i = 1; i < limit(); i++) {
			createOk(user, freshUrl());
		}
		finish(first, AnalysisStatus.COMPLETED);

		assertThat(deleteAnalysis(user, first).getResponse().getStatus()).isEqualTo(204);

		assertThat(used(user)).isEqualTo(limit());
		MvcResult refused = create(user, freshUrl());
		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(refused)).isEqualTo("USAGE_LIMIT_EXCEEDED");
	}

	@Test
	void deletingAFailedAnalysisDoesNotChangeTheCounterEither() throws Exception {
		User user = freeUser();
		UUID id = createOk(user, freshUrl());
		registrar.failAndRefund(id, "boom"); // the unit is already back
		assertThat(used(user)).isZero();

		deleteAnalysis(user, id);

		assertThat(used(user)).isZero(); // and deleting did not return a second one
	}

	// ---- duplicates ----------------------------------------------------------------------------

	@Test
	void theSameRepositoryWhileItRunsReturnsTheSameAnalysisAndChargesOnce() throws Exception {
		User user = freeUser();
		String url = freshUrl();

		UUID first = createOk(user, url);
		MvcResult again = create(user, url);

		assertThat(again.getResponse().getStatus()).isEqualTo(202);
		assertThat((String) JsonPath.read(body(again), "$.analysisId")).isEqualTo(first.toString());
		assertThat((String) JsonPath.read(body(again), "$.status")).isEqualTo("QUEUED");
		assertThat(used(user)).isEqualTo(1);
		assertThat(analysisRows(user)).isEqualTo(1);
		verify(pipeline, times(1)).run(any());
	}

	@Test
	void everyRunningStatusCountsAsInProgress() throws Exception {
		User user = freeUser();
		String url = freshUrl();
		UUID id = createOk(user, url);

		for (AnalysisStatus status : AnalysisRegistrar.IN_PROGRESS) {
			jdbc.update("update analyses set status = ? where id = ?", status.name(), id);
			assertThat(createOk(user, url)).isEqualTo(id);
		}

		assertThat(used(user)).isEqualTo(1);
	}

	@Test
	void aDifferentRepositoryIsAnotherCharge() throws Exception {
		User user = freeUser();

		UUID first = createOk(user, freshUrl());
		UUID second = createOk(user, freshUrl());

		assertThat(second).isNotEqualTo(first);
		assertThat(used(user)).isEqualTo(2);
	}

	@Test
	void afterItCompletesOrFailsTheSameRepositoryIsANewAnalysisAndANewCharge() throws Exception {
		User user = freeUser();
		String url = freshUrl();
		UUID first = createOk(user, url);
		finish(first, AnalysisStatus.COMPLETED);

		UUID second = createOk(user, url);

		assertThat(second).isNotEqualTo(first);
		assertThat(used(user)).isEqualTo(2);
		registrar.failAndRefund(second, "boom"); // a failure is a retry without a second charge
		UUID third = createOk(user, url);
		assertThat(third).isNotIn(first, second);
		assertThat(used(user)).isEqualTo(2);
	}

	@Test
	void atTheLimitResubmittingARunningRepositoryReturnsItInsteadOfRefusing() throws Exception {
		User user = freeUser();
		String running = freshUrl();
		UUID runningId = createOk(user, running);
		for (int i = 1; i < limit(); i++) {
			createOk(user, freshUrl());
		}
		assertThat(used(user)).isEqualTo(limit());

		MvcResult again = create(user, running);

		assertThat(again.getResponse().getStatus()).isEqualTo(202);
		assertThat((String) JsonPath.read(body(again), "$.analysisId")).isEqualTo(runningId.toString());
		assertThat(used(user)).isEqualTo(limit());
	}

	@Test
	void anotherUsersRunningAnalysisIsNeverReused() throws Exception {
		User user = freeUser();
		User other = freeUser();
		String url = freshUrl();

		UUID mine = createOk(user, url);
		UUID theirs = createOk(other, url);

		assertThat(theirs).isNotEqualTo(mine);
		assertThat(used(other)).isEqualTo(1);
	}

	// ---- failures and refunds ------------------------------------------------------------------

	@Test
	void aRejectedSubmissionIs503AndLeavesNoUnitBehind() throws Exception {
		User user = freeUser();
		doThrow(new TaskRejectedException("queue full")).when(pipeline).run(any());

		MvcResult busy = create(user, freshUrl());

		assertThat(busy.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(busy)).isEqualTo("SERVICE_BUSY");
		assertThat(used(user)).isZero();
		assertThat(chargedAndNotRefunded(user)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from analyses where user_id = ? and status = 'FAILED' and quota_refunded",
				Integer.class, user.getId())).isEqualTo(1);
	}

	@Test
	void aUserAtTheCapWhoseSubmissionWasRejectedCanStillSubmitLater() throws Exception {
		User user = freeUser();
		for (int i = 0; i < limit() - 1; i++) {
			createOk(user, freshUrl());
		}
		doThrow(new TaskRejectedException("queue full")).when(pipeline).run(any());
		assertThat(create(user, freshUrl()).getResponse().getStatus()).isEqualTo(503);
		org.mockito.Mockito.reset(pipeline);

		assertThat(create(user, freshUrl()).getResponse().getStatus()).isEqualTo(202);

		assertThat(used(user)).isEqualTo(limit());
	}

	@Test
	void aFailedAnalysisReturnsItsUnitOnceHoweverOftenTheRefundRuns() throws Exception {
		User user = freeUser();
		UUID id = createOk(user, freshUrl());
		assertThat(used(user)).isEqualTo(1);

		registrar.failAndRefund(id, "boom");
		assertThat(registrar.refundQuota(id)).isFalse();
		assertThat(registrar.refundQuota(id)).isFalse();

		assertThat(used(user)).isZero();
		assertThat(jdbc.queryForObject("select quota_refunded from analyses where id = ?", Boolean.class, id)).isTrue();
	}

	@Test
	void aCompletedAnalysisKeepsItsUnit() throws Exception {
		User user = freeUser();
		UUID id = createOk(user, freshUrl());

		finish(id, AnalysisStatus.COMPLETED);

		assertThat(used(user)).isEqualTo(1);
		assertThat(chargedAndNotRefunded(user)).isEqualTo(1);
	}

	@Test
	void anAnalysisThatWasNeverChargedIsNeverRefunded() throws Exception {
		User user = freeUser();
		createOk(user, freshUrl());
		Analysis legacy = analyses.save(Analysis.queued(freshUrl(), user.getId())); // as before usage tracking: not charged

		assertThat(registrar.refundQuota(legacy.getId())).isFalse();

		assertThat(used(user)).isEqualTo(1);
	}

	@Test
	void anAnalysisThatNoLongerExistsIsNotRefunded() throws Exception {
		User user = freeUser();
		UUID id = createOk(user, freshUrl());
		analyses.deleteById(id);

		assertThat(registrar.refundQuota(id)).isFalse();

		assertThat(used(user)).isEqualTo(1);
	}

	@Test
	void theCounterCannotGoBelowZero() throws Exception {
		User user = freeUser();
		UUID id = createOk(user, freshUrl());
		registrar.failAndRefund(id, "boom");
		assertThat(used(user)).isZero();

		transactions.executeWithoutResult(status -> usageService.release(user.getId(), UsageType.REPOSITORY_ANALYSIS));

		assertThat(used(user)).isZero();
		assertThatThrownBy(() -> jdbc.update("update user_usage set used_count = -1 where user_id = ?", user.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void thereIsOneRowPerUserAndType() throws Exception {
		User user = freeUser();
		createOk(user, freshUrl());

		assertThatThrownBy(() -> jdbc.update("""
				insert into user_usage (id, user_id, usage_type, used_count, created_at, updated_at)
				values (?, ?, 'REPOSITORY_ANALYSIS', 0, now(), now())""", UUID.randomUUID(), user.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theWritingMethodsRefuseToRunOutsideATransaction() {
		assertThatThrownBy(() -> usageService.charge(UUID.randomUUID(), UsageType.REPOSITORY_ANALYSIS))
				.isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
	}

	// ---- premium -------------------------------------------------------------------------------

	@Test
	void aPremiumUserIsNeverBlockedAndTheTrueCountIsShown() throws Exception {
		User user = premiumUser();
		int created = limit() + 5;

		for (int i = 0; i < created; i++) {
			createOk(user, freshUrl());
		}

		assertThat(used(user)).isEqualTo(created);
		String json = body(usage(user));
		assertThat((String) JsonPath.read(json, "$.plan")).isEqualTo("PREMIUM");
		assertThat((Integer) JsonPath.read(json, "$.usage[0].used")).isEqualTo(created);
		assertThat((Object) JsonPath.read(json, "$.usage[0].limit")).isNull();
		assertThat((Object) JsonPath.read(json, "$.usage[0].remaining")).isNull();
		assertThat((Boolean) JsonPath.read(json, "$.usage[0].limitReached")).isFalse();
		assertThat((Integer) JsonPath.read(json, "$.usage[0].length()")).isEqualTo(5); // the null fields are present, not omitted
	}

	@Test
	void aPremiumUsersFailedAnalysesAreStillRefunded() throws Exception {
		User user = premiumUser();
		UUID id = createOk(user, freshUrl());
		createOk(user, freshUrl());

		registrar.failAndRefund(id, "boom");

		assertThat(used(user)).isEqualTo(1);
	}

	@Test
	void anExpiredPremiumPlanFallsBackToTheFreeTierEnforcement() throws Exception {
		User user = newUser(Plan.PREMIUM, Instant.now().plus(Duration.ofDays(1)));
		for (int i = 0; i < limit() + 2; i++) {
			createOk(user, freshUrl());
		}
		user.changePlan(Plan.PREMIUM, Instant.now().minus(Duration.ofMinutes(1)));
		userService.save(user);

		MvcResult refused = create(user, freshUrl());

		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(refused)).isEqualTo("USAGE_LIMIT_EXCEEDED");
		String json = body(usage(user));
		assertThat((String) JsonPath.read(json, "$.plan")).isEqualTo("FREE");
		assertThat((Integer) JsonPath.read(json, "$.usage[0].used")).isEqualTo(limit() + 2);
		assertThat((Integer) JsonPath.read(json, "$.usage[0].limit")).isEqualTo(limit());
		assertThat((Integer) JsonPath.read(json, "$.usage[0].remaining")).isZero();
		assertThat((Boolean) JsonPath.read(json, "$.usage[0].limitReached")).isTrue();
	}

	@Test
	void aUserWhoDowngradesIsHeldToTheLifetimeCount() throws Exception {
		User user = premiumUser();
		for (int i = 0; i < limit() + 1; i++) {
			createOk(user, freshUrl());
		}
		user.changePlan(Plan.FREE, null);
		userService.save(user);

		assertThat(code(create(user, freshUrl()))).isEqualTo("USAGE_LIMIT_EXCEEDED");
	}

	@Test
	void aTokenCannotClaimAPlan() throws Exception {
		User user = freeUser(); // the token is minted for a FREE account; the plan is always read from the database
		for (int i = 0; i < limit(); i++) {
			createOk(user, freshUrl());
		}
		user.changePlan(Plan.PREMIUM, Instant.now().plus(Duration.ofDays(1)));
		userService.save(user);

		assertThat(create(user, freshUrl()).getResponse().getStatus()).isEqualTo(202); // upgrade applies at once
	}
}
