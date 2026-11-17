package com.abhiraj.archyra.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

import com.abhiraj.archyra.common.UsageLimitExceededException;
import com.abhiraj.archyra.security.EntitlementService;
import com.abhiraj.archyra.user.Plan;

@ExtendWith(MockitoExtension.class)
class UsageServiceTest {

	/** Bound here, the way the application binds it from configuration. */
	private static final UsageProperties PROPERTIES = new UsageProperties(new UsageProperties.FreeTier(4, 9));
	private static final int LIMIT = PROPERTIES.freeLimit(UsageType.REPOSITORY_ANALYSIS);
	private static final int AI_LIMIT = PROPERTIES.freeLimit(UsageType.AI_QUERY);

	@Mock
	private UserUsageRepository repository;
	@Mock
	private EntitlementService entitlements;
	private UsageService service;

	private final UUID user = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new UsageService(repository, PROPERTIES, entitlements);
	}

	private static UserUsage row(UUID user, UsageType type, int used) {
		UserUsage row = new UserUsage();
		ReflectionTestUtils.setField(row, "userId", user);
		ReflectionTestUtils.setField(row, "usageType", type);
		ReflectionTestUtils.setField(row, "usedCount", used);
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
				.hasMessageContaining(AI_LIMIT + " free AI queries");
	}

	// ---- release -------------------------------------------------------------------------------

	@Test
	void releaseUsesTheGuardedDecrement() {
		service.release(user, UsageType.REPOSITORY_ANALYSIS);

		verify(repository).decrement(user, "REPOSITORY_ANALYSIS");
	}

	// ---- reading -------------------------------------------------------------------------------

	@Test
	void aFreeUserSeesUsedLimitAndRemaining() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.REPOSITORY_ANALYSIS, LIMIT - 2)));

		UsageResponse response = service.usageOf(user);

		assertThat(response.plan()).isEqualTo(Plan.FREE);
		assertThat(response.usage()).containsExactly(
				new UsageEntry(UsageType.REPOSITORY_ANALYSIS, LIMIT - 2, LIMIT, 2, false),
				new UsageEntry(UsageType.AI_QUERY, 0, AI_LIMIT, AI_LIMIT, false));
	}

	@Test
	void aFreeUserAtTheCapHasNothingRemaining() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.REPOSITORY_ANALYSIS, LIMIT)));

		assertThat(service.usageOf(user).usage().getFirst()).isEqualTo(new UsageEntry(UsageType.REPOSITORY_ANALYSIS, LIMIT, LIMIT, 0, true));
	}

	@Test
	void aFormerPremiumUserOverTheCapShowsMoreUsedThanTheLimit() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.FREE);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.REPOSITORY_ANALYSIS, LIMIT + 8)));

		assertThat(service.usageOf(user).usage().getFirst())
				.isEqualTo(new UsageEntry(UsageType.REPOSITORY_ANALYSIS, LIMIT + 8, LIMIT, 0, true));
	}

	@Test
	void aPremiumUserIsUnlimitedWithNullsButTheTrueCount() {
		given(entitlements.effectivePlan(user)).willReturn(Plan.PREMIUM);
		given(repository.findByUserId(user)).willReturn(List.of(row(user, UsageType.REPOSITORY_ANALYSIS, LIMIT + 5)));

		UsageResponse response = service.usageOf(user);

		assertThat(response.plan()).isEqualTo(Plan.PREMIUM);
		assertThat(response.usage()).containsExactly(
				new UsageEntry(UsageType.REPOSITORY_ANALYSIS, LIMIT + 5, null, null, false),
				new UsageEntry(UsageType.AI_QUERY, 0, null, null, false));
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
		assertThat(UsageEntry.limited(UsageType.AI_QUERY, 0, 0)).isEqualTo(new UsageEntry(UsageType.AI_QUERY, 0, 0, 0, true));
	}
}
