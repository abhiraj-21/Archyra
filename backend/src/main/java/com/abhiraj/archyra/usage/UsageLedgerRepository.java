package com.abhiraj.archyra.usage;

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

	/** Inserts the row or raises it: the stored count is the maximum ever seen, whatever the order of concurrent calls. */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into usage_ledger (id, identity_key, usage_type, used_count, updated_at)
			values (:id, :key, :type, :count, now())
			on conflict (identity_key, usage_type)
			do update set used_count = greatest(usage_ledger.used_count, excluded.used_count), updated_at = now()
			""", nativeQuery = true)
	int raiseTo(@Param("id") UUID id, @Param("key") String key, @Param("type") String type, @Param("count") int count);
}
