package com.abhiraj.archyra.usage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Package-private on purpose: other features go through {@link UsageLedgerService}. */
interface UsageLedgerRepository extends JpaRepository<UsageLedgerEntry, UUID> {

	List<UsageLedgerEntry> findByIdentityKeyIn(Collection<String> identityKeys);

	/**
	 * Replaces the stored count of a lifetime type with the account's current one (deleting an account overwrites; only a
	 * restore or a merge takes the larger value, and those read the ledger, never write it).
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into usage_ledger (id, identity_key, usage_type, used_count, updated_at)
			values (:id, :key, :type, :count, now())
			on conflict (identity_key, usage_type)
			do update set used_count = excluded.used_count, window_start = null, updated_at = now()
			""", nativeQuery = true)
	int overwrite(@Param("id") UUID id, @Param("key") String key, @Param("type") String type, @Param("count") int count);

	/** {@link #overwrite} for a weekly type, with the window the account's count belongs to. */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into usage_ledger (id, identity_key, usage_type, used_count, window_start, updated_at)
			values (:id, :key, :type, :count, :window, now())
			on conflict (identity_key, usage_type)
			do update set used_count = excluded.used_count, window_start = excluded.window_start, updated_at = now()
			""", nativeQuery = true)
	int overwriteInWindow(@Param("id") UUID id, @Param("key") String key, @Param("type") String type, @Param("count") int count,
			@Param("window") Instant window);

	/** An account that no longer has a count of this type leaves no row for it. */
	@Modifying(flushAutomatically = true)
	@Query("delete from UsageLedgerEntry e where e.identityKey = :key and e.usageType = :type")
	int deleteByKeyAndType(@Param("key") String key, @Param("type") String type);
}
