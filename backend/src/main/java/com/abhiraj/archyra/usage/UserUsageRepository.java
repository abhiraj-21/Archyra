package com.abhiraj.archyra.usage;

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
	 * Bounds how long this transaction waits for a row lock, so a stuck holder cannot pile up request threads.
	 * PostgreSQL ignores Hibernate's lock-timeout hint for {@code FOR UPDATE}, so it is set on the session
	 * ({@code set_config(..., true)} lasts until the transaction ends).
	 */
	@Query(value = "select set_config('lock_timeout', :millis, true)", nativeQuery = true)
	String setLockTimeout(@Param("millis") String millis);
}
