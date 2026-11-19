package com.abhiraj.archyra.analysis;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

	List<Analysis> findByStatusNotIn(Collection<AnalysisStatus> statuses);

	Page<Analysis> findByOwnerId(UUID ownerId, Pageable pageable);

	/** Rows from before accounts existed. */
	List<Analysis> findByOwnerIdIsNull();

	/** The newest analysis of this user and repository that is in one of the given statuses. */
	Optional<Analysis> findFirstByOwnerIdAndRepositoryUrlAndStatusInOrderByCreatedAtDesc(
			UUID ownerId, String repositoryUrl, Collection<AnalysisStatus> statuses);

	/**
	 * The guard that makes a refund happen once: flips {@code quota_refunded} only for a charged, not yet refunded
	 * analysis. 0 rows means already refunded, never charged, or gone.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Analysis a set a.quotaRefunded = true where a.id = :id and a.quotaCharged = true and a.quotaRefunded = false")
	int markQuotaRefunded(@Param("id") UUID id);

	boolean existsByIdAndStatus(UUID id, AnalysisStatus status);

	@Query("select a.id from Analysis a where a.status = :status and a.id in :ids")
	List<UUID> findIdsByStatusAndIdIn(@Param("status") AnalysisStatus status, @Param("ids") Collection<UUID> ids);

	@Query("select a.ownerId from Analysis a where a.id = :id")
	Optional<UUID> findOwnerId(@Param("id") UUID id);

	/** Failed analyses whose unit was never returned, for the startup repair. */
	List<Analysis> findByStatusAndQuotaChargedTrueAndQuotaRefundedFalse(AnalysisStatus status);
}
