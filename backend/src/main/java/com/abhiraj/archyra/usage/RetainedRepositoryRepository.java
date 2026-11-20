package com.abhiraj.archyra.usage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Package-private on purpose: other features go through {@link UsageLedgerService}. */
interface RetainedRepositoryRepository extends JpaRepository<RetainedRepository, UUID> {

	List<RetainedRepository> findByIdentityKeyIn(Collection<String> identityKeys);

	/** Part of replacing a key's repository set: the old rows go before the current ones are inserted. */
	@Modifying(flushAutomatically = true)
	@Query("delete from RetainedRepository r where r.identityKey = :key")
	int deleteByIdentityKey(@Param("key") String key);
}
