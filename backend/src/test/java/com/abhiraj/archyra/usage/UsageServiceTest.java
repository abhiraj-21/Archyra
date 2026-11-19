package com.abhiraj.archyra.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.ai.llm.MovableClock;
import com.abhiraj.archyra.common.UsageLimitExceededException;
import com.abhiraj.archyra.security.EntitlementService;
import com.abhiraj.archyra.user.Plan;

@ExtendWith(MockitoExtension.class)
class UsageServiceTest {

	/** Bound here, the way the application binds it from configuration. */
	private static final UsageProperties PROPERTIES = new UsageProperties(new UsageProperties.FreeTier(4, 9),
			"archyra-test-only-usage-ledger-secret-0123456789", "Asia/Kolkata");
	/** Wednesday 2026-11-18 in Kolkata; the week started Monday 2026-11-16 00:00 IST and the next one starts 2026-11-23 00:00 IST. */
	private static final Instant WEDNESDAY = Instant.parse("2026-11-18T10:00:00Z");
	private static final Instant WEEK_START = Instant.parse("2026-11-15T18:30:00Z");
	private static final Instant NEXT_WEEK_START = Instant.parse("2026-11-22T18:30:00Z");
	private static final int LIMIT = PROPERTIES.freeLimit(UsageType.REPOSITORY_ANALYSIS);
	private static final int AI_LIMIT = PROPERTIES.freeLimit(UsageType.AI_QUERY);

	@Mock
	private UserUsageRepository repository;
	@Mock
	private EntitlementService entitlements;
	private final MovableClock clock = new MovableClock();
	private UsageService service;

	private final UUID user = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		clock.setTo(WEDNESDAY);
		service = new UsageService(repository, PROPERTIES, entitlements, new UsageWindows(PROPERTIES, clock));
	}

	private static UserUsage row(UUID user, UsageType type, int used) {
		return row(user, type, used, type.weekly() ? WEEK_START : null);
	}

	private static UserUsage row(UUID user, UsageType type, int used, Instant windowStart) {
		UserUsage row = new UserUsage();
		ReflectionTestUtils.setField(row, "userId", user);
		ReflectionTestUtils.setField(row, "usageType", type);
		ReflectionTestUtils.setField(row, "usedCount", used);
		ReflectionTestUtils.setField(row, "windowStart", windowStart);
		return row;
	}

	private UserUsage lockedRow(int used) {
		UserUsage row = row(user, UsageType.REPOSITORY_ANALYSIS, used);
		given(repository.findForUpdate(user, UsageType.REPOSITORY_ANALYSIS)).willReturn(Optional.of(row));
		return row;
	}

	// ---- lock ----------------------------------------------------------------------------------

	@Test
	void lockBoundsTheWaitCreatesTheRowIfMissingAndThenLocksIt() {
		lockedRow(0);

		service.lock(user, UsageType.REPOSITORY_ANALYSIS);

		InOrder order = inOrder(repository);
		order.verify(repository).setLockTimeout(UsageService.LOCK_TIMEOUT_MILLIS);
		order.verify(repository).insertIfAbsent(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(user),
				org.mockito.ArgumentMatchers.eq("REPOSITORY_ANALYSIS"));
		order.verify(repository).findForUpdate(user, UsageType.REPOSITORY_ANALYSIS);
	}

	@Test
	void aWeeklyRowIsCreatedInTheCurrentWindow() {
		given(repository.findForUpdate(user, UsageType.AI_QUERY)).willReturn(Optional.of(row(user, UsageType.AI_QUERY, 0, WEEK_START)));

		service.lock(user, UsageType.AI_QUERY);

		verify(repository).insertIfAbsentInWindow(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(user),
				org.mockito.ArgumentMatchers.eq("AI_QUERY"), org.mockito.ArgumentMatchers.eq(WEEK_START));
		verify(repository, never()).insertIfAbsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
	}

	@Test
	void lockingAnOldWeeklyRowStartsANewWindowAtZero() {
		UserUsage old = row(user, UsageType.AI_QUERY, AI_LIMIT, WEEK_START.minus(Duration.ofDays(7)));
		given(repository.findForUpdate(user, UsageType.AI_QUERY)).willReturn(Optional.of(old));

		service.lock(user, UsageType.AI_QUERY);

		assertThat(old.getUsedCount()).isZero();
		assertThat(old.getWindowStart()).isEqualTo(WEEK_START);
	}

	@Test
	void aWeeklyRowFromBeforeTheChangeHasNoWindowAndStartsAtZero() {
		UserUsage legacy = row(user, UsageType.AI_QUERY, 20, null);
		given(repository.findForUpdate(user, UsageType.AI_QUERY)).willReturn(Optional.of(legacy));
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);

		Instant window = service.charge(user, UsageType.AI_QUERY);

		assertThat(legacy.getUsedCount()).isEqualTo(1); // reset to 0, then this question
		assertThat(window).isEqualTo(WEEK_START);
	}

	@Test
	void aLifetimeRowNeverRollsOver() {
		UserUsage row = lockedRow(LIMIT - 1);
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		clock.setTo(WEDNESDAY.plus(Duration.ofDays(400)));

		Instant window = service.charge(user, UsageType.REPOSITORY_ANALYSIS);

		assertThat(row.getUsedCount()).isEqualTo(LIMIT);
		assertThat(window).isNull();
		assertThat(row.getWindowStart()).isNull();
	}

	// ---- charge --------------------------------------------------------------------------------

	@Test
	void aFreeUserBelowTheLimitIsCharged() {
		UserUsage row = lockedRow(LIMIT - 1);
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);

		service.charge(user, UsageType.REPOSITORY_ANALYSIS);

		assertThat(row.getUsedCount()).isEqualTo(LIMIT);
	}

	@Test
	void aFreeUserAtTheLimitIsRefusedWithTheConfiguredLimitInTheMessage() {
		UserUsage row = lockedRow(LIMIT);
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);

		assertThatThrownBy(() -> service.charge(user, UsageType.REPOSITORY_ANALYSIS))
				.isInstanceOf(UsageLimitExceededException.class)
				.hasMessage("You have used all " + LIMIT + " free repository analyses. Upgrade to Archyra Premium to continue.");
		assertThat(row.getUsedCount()).isEqualTo(LIMIT);
	}

	@Test
	void aFormerPremiumUserOverTheLimitIsRefused() {
		UserUsage row = lockedRow(LIMIT + 7);
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);

		assertThatThrownBy(() -> service.charge(user, UsageType.REPOSITORY_ANALYSIS)).isInstanceOf(UsageLimitExceededException.class);
		assertThat(row.getUsedCount()).isEqualTo(LIMIT + 7);
	}

	@Test
	void aPremiumUserIsNeverBlockedAndStillCounted() {
		UserUsage row = lockedRow(LIMIT + 5);
		given(entitlements.effectivePlan(user)).willReturn(Plan.PREMIUM);

		service.charge(user, UsageType.REPOSITORY_ANALYSIS);

		assertThat(row.getUsedCount()).isEqualTo(LIMIT + 6);
	}

	@Test
	void chargingWithoutTheRowBeingCreatedFirstIsAProgrammingError() {
		given(repository.findForUpdate(user, UsageType.REPOSITORY_ANALYSIS)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.charge(user, UsageType.REPOSITORY_ANALYSIS)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theLimitOfTheChargedTypeIsUsed() {
		UserUsage row = row(user, UsageType.AI_QUERY, AI_LIMIT);
		given(repository.findForUpdate(user, UsageType.AI_QUERY)).willReturn(Optional.of(row));
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);

		assertThatThrownBy(() -> service.charge(user, UsageType.AI_QUERY)).isInstanceOf(UsageLimitExceededException.class)
				.hasMessageContaining(AI_LIMIT + " free AI queries for this week")
				.satisfies(ex -> assertThat(((UsageLimitExceededException) ex).resetsAt()).isEqualTo(NEXT_WEEK_START));
	}

	@Test
	void theLifetimeLimitErrorHasNoResetTime() {
		lockedRow(LIMIT);
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);

		assertThatThrownBy(() -> service.charge(user, UsageType.REPOSITORY_ANALYSIS))
				.satisfies(ex -> assertThat(((UsageLimitExceededException) ex).resetsAt()).isNull());
	}

	@Test
	void aWeeklyRowAtTheLimitIsAcceptedAgainOnceTheWeekTurns() {
		UserUsage row = row(user, UsageType.AI_QUERY, AI_LIMIT);
		given(repository.findForUpdate(user, UsageType.AI_QUERY)).willReturn(Optional.of(row));
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		assertThatThrownBy(() -> service.charge(user, UsageType.AI_QUERY)).isInstanceOf(UsageLimitExceededException.class);

		clock.setTo(NEXT_WEEK_START); // exactly Monday 00:00 IST

		assertThat(service.charge(user, UsageType.AI_QUERY)).isEqualTo(NEXT_WEEK_START);
		assertThat(row.getUsedCount()).isEqualTo(1);
	}

	@Test
	void aPremiumUserOnAWeeklyTypeIsCountedAndNeverBlocked() {
		UserUsage row = row(user, UsageType.AI_QUERY, AI_LIMIT + 3);
		given(repository.findForUpdate(user, UsageType.AI_QUERY)).willReturn(Optional.of(row));
		given(entitlements.effectivePlan(user)).willReturn(Plan.PREMIUM);

		service.charge(user, UsageType.AI_QUERY);

		assertThat(row.getUsedCount()).isEqualTo(AI_LIMIT + 4);
	}

	// ---- release -------------------------------------------------------------------------------

	@Test
	void releaseUsesTheGuardedDecrement() {
		service.release(user, UsageType.REPOSITORY_ANALYSIS);

		verify(repository).decrement(user, "REPOSITORY_ANALYSIS");
	}

	@Test
	void aWeeklyUnitIsReturnedOnlyInTheWindowItWasChargedIn() {
		service.release(user, UsageType.AI_QUERY, WEEK_START);

		verify(repository).decrementInWindow(user, "AI_QUERY", WEEK_START);
		verify(repository, never()).decrement(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void aWeeklyRefundAfterTheResetChangesNothing() {
		clock.setTo(NEXT_WEEK_START);

		service.release(user, UsageType.AI_QUERY, WEEK_START);

		verify(repository, never()).decrementInWindow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
		verify(repository, never()).decrement(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void aWeeklyRefundOneSecondBeforeTheResetStillCounts() {
		clock.setTo(NEXT_WEEK_START.minusSeconds(1));

		service.release(user, UsageType.AI_QUERY, WEEK_START);

		verify(repository).decrementInWindow(user, "AI_QUERY", WEEK_START);
	}

	@Test
	void aWeeklyRefundWithoutAWindowChangesNothing() {
		service.release(user, UsageType.AI_QUERY, null);

		verify(repository, never()).decrementInWindow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
	}

	@Test
	void aWeeklyUnitCannotBeReleasedWithoutItsWindow() {
		assertThatThrownBy(() -> service.release(user, UsageType.AI_QUERY)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aUserIsHeldToTheFreeLimitOnlyWhileTheEffectivePlanIsFree() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE, Plan.PREMIUM);

		assertThat(service.isHeldToFreeLimit(user)).isTrue();
		assertThat(service.isHeldToFreeLimit(user)).isFalse();
	}

	// ---- reading -------------------------------------------------------------------------------

	@Test
	void aFreeUserSeesUsedLimitAndRemaining() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.REPOSITORY_ANALYSIS, LIMIT - 2)));

		UsageResponse response = service.usageOf(user);

		assertThat(response.plan()).isEqualTo(Plan.FREE);
		assertThat(response.usage()).containsExactly(
				new UsageEntry(UsageType.REPOSITORY_ANALYSIS, LIMIT - 2, LIMIT, 2, false, null),
				new UsageEntry(UsageType.AI_QUERY, 0, AI_LIMIT, AI_LIMIT, false, NEXT_WEEK_START));
	}

	@Test
	void aFreeUserAtTheCapHasNothingRemaining() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.REPOSITORY_ANALYSIS, LIMIT)));

		assertThat(service.usageOf(user).usage().getFirst()).isEqualTo(new UsageEntry(UsageType.REPOSITORY_ANALYSIS, LIMIT, LIMIT, 0, true, null));
	}

	@Test
	void aFormerPremiumUserOverTheCapShowsMoreUsedThanTheLimit() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.REPOSITORY_ANALYSIS, LIMIT + 8)));

		assertThat(service.usageOf(user).usage().getFirst())
				.isEqualTo(new UsageEntry(UsageType.REPOSITORY_ANALYSIS, LIMIT + 8, LIMIT, 0, true, null));
	}

	@Test
	void aPremiumUserIsUnlimitedWithNullsButTheTrueCount() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.PREMIUM);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.REPOSITORY_ANALYSIS, LIMIT + 5)));

		UsageResponse response = service.usageOf(user);

		assertThat(response.plan()).isEqualTo(Plan.PREMIUM);
		assertThat(response.usage()).containsExactly(
				new UsageEntry(UsageType.REPOSITORY_ANALYSIS, LIMIT + 5, null, null, false, null),
				new UsageEntry(UsageType.AI_QUERY, 0, null, null, false, NEXT_WEEK_START));
	}

	@Test
	void everyUsageTypeIsListedEvenWithoutRowsAndReadingNeverWrites() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of());

		UsageResponse response = service.usageOf(user);

		assertThat(response.usage()).extracting(UsageEntry::usageType).containsExactly(UsageType.values());
		assertThat(response.usage()).allMatch(entry -> entry.used() == 0);
		verify(repository, never()).insertIfAbsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
		verify(repository, never()).findForUpdate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void aLimitOfZeroIsReachedImmediately() {
		assertThat(UsageEntry.limited(UsageType.AI_QUERY, 0, 0, null)).isEqualTo(new UsageEntry(UsageType.AI_QUERY, 0, 0, 0, true, null));
	}

	@Test
	void aWeeklyCountOfAnEarlierWeekReadsAsZeroWithoutWriting() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.AI_QUERY, AI_LIMIT, WEEK_START.minus(Duration.ofDays(7)))));

		UsageEntry entry = service.usageOf(user).usage().get(1);

		assertThat(entry).isEqualTo(new UsageEntry(UsageType.AI_QUERY, 0, AI_LIMIT, AI_LIMIT, false, NEXT_WEEK_START));
		verify(repository, never()).findForUpdate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void aWeeklyCountWithoutAWindowReadsAsZero() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.AI_QUERY, 20, null)));

		assertThat(service.usageOf(user).usage().get(1).used()).isZero();
	}

	@Test
	void aWeeklyCountOfTheCurrentWeekIsShownWithTheResetTime() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.AI_QUERY, AI_LIMIT, WEEK_START)));

		assertThat(service.usageOf(user).usage().get(1))
				.isEqualTo(new UsageEntry(UsageType.AI_QUERY, AI_LIMIT, AI_LIMIT, 0, true, NEXT_WEEK_START));
	}
}
