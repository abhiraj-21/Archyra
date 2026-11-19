package com.abhiraj.archyra.usage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** Package-private on purpose: other features go through {@link UsageService}. */
interface UserUsageRepository extends JpaRepository<UserUsage, UUID> {

	List<UserUsage> findByUserId(UUID userId);

	/** {@code SELECT ... FOR UPDATE}: serializes everything that consumes this user's quota until the transaction ends. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UserUsage u where u.userId = :userId and u.usageType = :type")
	Optional<UserUsage> findForUpdate(@Param("userId") UUID userId, @Param("type") UsageType type);

	/**
	 * Creates the row if it does not exist. Concurrent first-time requests cannot violate the unique constraint: the
	 * loser of the race waits for the winner and then does nothing.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into user_usage (id, user_id, usage_type, used_count, created_at, updated_at)
			values (:id, :userId, :type, 0, now(), now())
			on conflict (user_id, usage_type) do nothing
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("type") String type);

	/** Like {@link #insertIfAbsent} for a weekly type: a new row starts in the given window. */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into user_usage (id, user_id, usage_type, used_count, window_start, created_at, updated_at)
			values (:id, :userId, :type, 0, :window, now(), now())
			on conflict (user_id, usage_type) do nothing
			""", nativeQuery = true)
	int insertIfAbsentInWindow(@Param("id") UUID id, @Param("userId") UUID userId, @Param("type") String type,
			@Param("window") Instant window);

	/**
	 * Raises the counter to {@code count} (creating the row if needed) and never lowers it. Used only to restore what the
	 * usage ledger remembers, so it is a plain atomic upsert and does not take the row lock of {@link #findForUpdate}.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into user_usage (id, user_id, usage_type, used_count, created_at, updated_at)
			values (:id, :userId, :type, :count, now(), now())
			on conflict (user_id, usage_type)
			do update set used_count = greatest(user_usage.used_count, excluded.used_count), updated_at = now()
			""", nativeQuery = true)
	int raiseTo(@Param("id") UUID id, @Param("userId") UUID userId, @Param("type") String type, @Param("count") int count);

	/**
	 * {@link #raiseTo} for a weekly type, in the window {@code window} (the current one). A stored count only competes with
	 * a count of the same window: the same window keeps the larger count, an older (or missing) window is replaced, and a
	 * newer window is kept. Postgres evaluates every SET expression against the old row, so the three branches agree.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into user_usage (id, user_id, usage_type, used_count, window_start, created_at, updated_at)
			values (:id, :userId, :type, :count, :window, now(), now())
			on conflict (user_id, usage_type)
			do update set
				used_count = case
					when user_usage.window_start = excluded.window_start then greatest(user_usage.used_count, excluded.used_count)
					when user_usage.window_start is null or user_usage.window_start < excluded.window_start then excluded.used_count
					else user_usage.used_count end,
				window_start = greatest(user_usage.window_start, excluded.window_start),
				updated_at = now()
			""", nativeQuery = true)
	int raiseToInWindow(@Param("id") UUID id, @Param("userId") UUID userId, @Param("type") String type, @Param("count") int count,
			@Param("window") Instant window);

	/** Removes every counter of a deleted account (its consumption lives on in the ledger). */
	@Modifying(flushAutomatically = true)
	@Query("delete from UserUsage u where u.userId = :userId")
	int deleteAllOf(@Param("userId") UUID userId);

	/** Returns one unit. The guard keeps the counter at 0 or more even if this is called too often. */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			update user_usage set used_count = used_count - 1, updated_at = now()
			where user_id = :userId and usage_type = :type and used_count > 0
			""", nativeQuery = true)
	int decrement(@Param("userId") UUID userId, @Param("type") String type);

	/**
	 * Returns one unit of a weekly type, only if the row is still in the window the unit was charged in: the row has
	 * rolled over (or never was in that window) means no row matches and nothing changes, so a refund can neither go below
	 * 0 nor credit a newer week.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			update user_usage set used_count = used_count - 1, updated_at = now()
			where user_id = :userId and usage_type = :type and used_count > 0 and window_start = :window
			""", nativeQuery = true)
	int decrementInWindow(@Param("userId") UUID userId, @Param("type") String type, @Param("window") Instant window);

	/**
	 * Bounds how long this transaction waits for a row lock, so a stuck holder cannot pile up request threads.
	 * PostgreSQL ignores Hibernate's lock-timeout hint for {@code FOR UPDATE}, so it is set on the session
	 * ({@code set_config(..., true)} lasts until the transaction ends).
	 */
	@Query(value = "select set_config('lock_timeout', :millis, true)", nativeQuery = true)
	String setLockTimeout(@Param("millis") String millis);
}
