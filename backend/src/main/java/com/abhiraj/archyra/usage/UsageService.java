package com.abhiraj.archyra.usage;

import java.time.Instant;
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
 * Usage counters: lifetime ones, and weekly ones for the types with {@link UsageType#weekly()}. The counters are written
 * only inside a transaction that the caller owns (the writing methods are {@code MANDATORY}), so a charge commits or
 * rolls back together with whatever it pays for.
 *
 * <p>Weekly types reset lazily, with no scheduler: a row remembers the {@code window_start} its count belongs to, and the
 * first thing {@link #lock} and {@link #charge} do with a weekly row is start a new window (count 0) if the row's one is
 * missing or over, inside the same locked transaction. Reading does the same arithmetic without writing. A refund
 * ({@link #release(UUID, UsageType, Instant)}) only counts in the window the unit was charged in.
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
	private final UsageWindows windows;

	/** Makes sure the row exists and locks it. Call first; the lock lasts until the surrounding transaction ends. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lock(UUID userId, UsageType type) {
		repository.setLockTimeout(LOCK_TIMEOUT_MILLIS);
		if (type.weekly()) {
			repository.insertIfAbsentInWindow(UUID.randomUUID(), userId, type.name(), windows.currentStart());
		}
		else {
			repository.insertIfAbsent(UUID.randomUUID(), userId, type.name());
		}
		lockedRow(userId, type);
	}

	/**
	 * Consumes one unit. FREE users are held to the free-tier limit, PREMIUM users are only counted.
	 *
	 * @return the window the unit was charged in, which a weekly unit needs to be refunded (null for a lifetime type)
	 * @throws UsageLimitExceededException if a FREE user has used up the allowance (a weekly one says when it resets)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Instant charge(UUID userId, UsageType type) {
		UserUsage row = lockedRow(userId, type);
		if (entitlements.effectivePlan(userId) == Plan.FREE) {
			int limit = properties.freeLimit(type);
			if (row.getUsedCount() >= limit) {
				throw new UsageLimitExceededException("You have used all " + limit + " free " + type.displayName()
						+ (type.weekly() ? " for this week" : "") + ". Upgrade to Archyra Premium to continue.", resetsAt(type));
			}
		}
		row.increment();
		return row.getWindowStart();
	}

	/**
	 * Returns one unit of a lifetime type; never goes below 0. The caller guarantees it is done once per charge (see
	 * AnalysisRegistrar).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(UUID userId, UsageType type) {
		if (type.weekly()) {
			throw new IllegalArgumentException("A weekly unit is released with the window it was charged in");
		}
		repository.decrement(userId, type.name());
	}

	/**
	 * Returns one unit of {@code type}, charged in the window starting at {@code chargedWindow} (what {@link #charge}
	 * returned). A weekly unit is only returned while that is still the current window: after a reset it is a no-op, so the
	 * count cannot go negative and the new week is never credited. A null window (a unit charged before the allowance was
	 * weekly) is also a no-op. The caller guarantees it is done once per charge (see ChatTransactions).
	 *
	 * <p>No rollover is written here: a refund of an old window is decided from the reservation, and one of the current
	 * window finds the row already in it, because the charge put it there. The guarded UPDATE matches the window too, so
	 * a row that has moved on is untouched even if the clock disagrees.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(UUID userId, UsageType type, Instant chargedWindow) {
		if (!type.weekly()) {
			repository.decrement(userId, type.name());
			return;
		}
		if (chargedWindow == null || chargedWindow.isBefore(windows.currentStart())) {
			return;
		}
		repository.decrementInWindow(userId, type.name(), chargedWindow);
	}

	/** True while the user is held to the free-tier limits (FREE, or PREMIUM that has expired), as read from the database now. */
	@Transactional(readOnly = true)
	public boolean isHeldToFreeLimit(UUID userId) {
		return entitlements.effectivePlan(userId) == Plan.FREE;
	}

	/** The caller's own counters. Read-only: it never creates a row and takes no lock. */
	@Transactional(readOnly = true)
	public UsageResponse usageOf(UUID userId) {
		Plan plan = entitlements.effectivePlan(userId);
		Instant windowStart = windows.currentStart();
		Map<UsageType, Integer> used = new EnumMap<>(UsageType.class);
		repository.findByUserId(userId).forEach(row -> used.put(row.getUsageType(),
				row.getUsageType().weekly() && row.isBefore(windowStart) ? 0 : row.getUsedCount()));
		return new UsageResponse(plan, Arrays.stream(UsageType.values())
				.map(type -> entry(plan, type, used.getOrDefault(type, 0))).toList());
	}

	private UsageEntry entry(Plan plan, UsageType type, int used) {
		Instant resetsAt = resetsAt(type);
		return plan == Plan.PREMIUM ? UsageEntry.unlimited(type, used, resetsAt)
				: UsageEntry.limited(type, used, properties.freeLimit(type), resetsAt);
	}

	/** When a weekly type starts again; null for a lifetime type. */
	private Instant resetsAt(UsageType type) {
		return type.weekly() ? windows.nextReset() : null;
	}

	/** The locked row, in the current window if the type is weekly (an old or missing window is rolled over to 0 here). */
	private UserUsage lockedRow(UUID userId, UsageType type) {
		UserUsage row = repository.findForUpdate(userId, type).orElseThrow(() -> new IllegalStateException("Usage row missing; lock() was not called"));
		if (type.weekly()) {
			row.rollOverTo(windows.currentStart());
		}
		return row;
	}
}
