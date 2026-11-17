package com.abhiraj.archyra.usage;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.common.UsageLimitExceededException;
import com.abhiraj.archyra.security.EntitlementService;
import com.abhiraj.archyra.user.Plan;

import lombok.RequiredArgsConstructor;

/**
 * Lifetime usage counters. The counters are written only inside a transaction that the caller owns (the writing
 * methods are {@code MANDATORY}), so a charge commits or rolls back together with whatever it pays for.
 *
 * <p>Concurrency: {@link #lock} takes {@code SELECT ... FOR UPDATE} on the user's row for the type, which serializes
 * everything that consumes that user's quota, and nobody else's, until the caller's transaction ends.
 *
 * <p>Whether the limit applies comes from {@link EntitlementService#effectivePlan}, never from the token.
 */
@Service
@RequiredArgsConstructor
public class UsageService {

	/** How long a request may wait for another request of the same user before it is answered as busy. */
	static final String LOCK_TIMEOUT_MILLIS = "5000";

	private final UserUsageRepository repository;
	private final UsageProperties properties;
	private final EntitlementService entitlements;

	/** Makes sure the row exists and locks it. Call first; the lock lasts until the surrounding transaction ends. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lock(UUID userId, UsageType type) {
		repository.setLockTimeout(LOCK_TIMEOUT_MILLIS);
		repository.insertIfAbsent(UUID.randomUUID(), userId, type.name());
		lockedRow(userId, type);
	}

	/**
	 * Consumes one unit. FREE users are held to the free-tier limit, PREMIUM users are only counted.
	 *
	 * @throws UsageLimitExceededException if a FREE user has used up the allowance
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void charge(UUID userId, UsageType type) {
		UserUsage row = lockedRow(userId, type);
		if (entitlements.effectivePlan(userId) == Plan.FREE) {
			int limit = properties.freeLimit(type);
			if (row.getUsedCount() >= limit) {
				throw new UsageLimitExceededException(
						"You have used all " + limit + " free " + type.displayName() + ". Upgrade to Archyra Premium to continue.");
			}
		}
		row.increment();
	}

	/** Returns one unit; never goes below 0. The caller guarantees it is done once per charge (see AnalysisRegistrar). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(UUID userId, UsageType type) {
		repository.decrement(userId, type.name());
	}

	/** The caller's own counters. Read-only: it never creates a row and takes no lock. */
	@Transactional(readOnly = true)
	public UsageResponse usageOf(UUID userId) {
		Plan plan = entitlements.effectivePlan(userId);
		Map<UsageType, Integer> used = new EnumMap<>(UsageType.class);
		repository.findByUserId(userId).forEach(row -> used.put(row.getUsageType(), row.getUsedCount()));
		return new UsageResponse(plan, Arrays.stream(UsageType.values())
				.map(type -> entry(plan, type, used.getOrDefault(type, 0))).toList());
	}

	private UsageEntry entry(Plan plan, UsageType type, int used) {
		return plan == Plan.PREMIUM ? UsageEntry.unlimited(type, used) : UsageEntry.limited(type, used, properties.freeLimit(type));
	}

	private UserUsage lockedRow(UUID userId, UsageType type) {
		return repository.findForUpdate(userId, type).orElseThrow(() -> new IllegalStateException("Usage row missing; lock() was not called"));
	}
}
