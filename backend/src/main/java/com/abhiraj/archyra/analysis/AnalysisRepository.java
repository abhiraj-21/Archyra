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

	List<Analysis> findByOwnerId(UUID ownerId);

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

	/**
	 * The analyses of this user that still hold a credit: charged and not refunded (completed, running and restored
	 * ones; a failed one was refunded).
	 */
	List<Analysis> findByOwnerIdAndQuotaChargedTrueAndQuotaRefundedFalse(UUID ownerId);

	/**
	 * NOT_ANALYZED to QUEUED, for the owner only, keeping {@code quota_charged}. The guard is the status: of two
	 * simultaneous requests exactly one updates a row, and only that one starts a pipeline run.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Analysis a set a.status = com.abhiraj.archyra.analysis.AnalysisStatus.QUEUED, a.progress = 0 "
			+ "where a.id = :id and a.ownerId = :ownerId and a.status = com.abhiraj.archyra.analysis.AnalysisStatus.NOT_ANALYZED")
	int queueRestored(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

	/** Puts an entry that could not be handed to the (full) queue back to NOT_ANALYZED: nothing is charged or refunded. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Analysis a set a.status = com.abhiraj.archyra.analysis.AnalysisStatus.NOT_ANALYZED, a.progress = 0 "
			+ "where a.id = :id and a.status = com.abhiraj.archyra.analysis.AnalysisStatus.QUEUED")
	int unqueueRestored(@Param("id") UUID id);

	/** Deletes the row unless a run owns it (queued or running). 0 rows: it is gone already, or a run owns it. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Analysis a where a.id = :id and a.status in (com.abhiraj.archyra.analysis.AnalysisStatus.COMPLETED, "
			+ "com.abhiraj.archyra.analysis.AnalysisStatus.FAILED, com.abhiraj.archyra.analysis.AnalysisStatus.NOT_ANALYZED)")
	int deleteIfIdle(@Param("id") UUID id);

	boolean existsByIdAndStatus(UUID id, AnalysisStatus status);

	@Query("select a.id from Analysis a where a.status = :status and a.id in :ids")
	List<UUID> findIdsByStatusAndIdIn(@Param("status") AnalysisStatus status, @Param("ids") Collection<UUID> ids);

	@Query("select a.ownerId from Analysis a where a.id = :id")
	Optional<UUID> findOwnerId(@Param("id") UUID id);

	/** Failed analyses whose unit was never returned, for the startup repair. */
	List<Analysis> findByStatusAndQuotaChargedTrueAndQuotaRefundedFalse(AnalysisStatus status);
}
