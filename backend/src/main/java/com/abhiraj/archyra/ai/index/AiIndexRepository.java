package com.abhiraj.archyra.ai.index;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Package-private on purpose: other features and the controller go through {@link AiIndexService}. */
interface AiIndexRepository extends JpaRepository<AiIndex, UUID> {

	List<AiIndex> findByStatus(IndexStatus status);

	@Query("select i.analysisId from AiIndex i")
	List<UUID> findAllAnalysisIds();

	/** Each of these only moves a row out of the status named in its WHERE clause; 0 rows means someone else got there first, or it is gone. */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiIndex i set i.status = com.abhiraj.archyra.ai.index.IndexStatus.READY, i.indexedAt = :now, i.updatedAt = :now, i.failureReason = :note "
			+ "where i.analysisId = :id and i.status = com.abhiraj.archyra.ai.index.IndexStatus.INDEXING")
	int markReady(@Param("id") UUID id, @Param("now") Instant now, @Param("note") String note);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiIndex i set i.status = com.abhiraj.archyra.ai.index.IndexStatus.FAILED, i.updatedAt = :now, i.failureReason = :reason "
			+ "where i.analysisId = :id and i.status = com.abhiraj.archyra.ai.index.IndexStatus.INDEXING")
	int markFailed(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") String reason);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiIndex i set i.status = com.abhiraj.archyra.ai.index.IndexStatus.INDEXING, i.updatedAt = :now, i.failureReason = :note "
			+ "where i.analysisId = :id and i.status = com.abhiraj.archyra.ai.index.IndexStatus.FAILED")
	int resumeFromFailed(@Param("id") UUID id, @Param("now") Instant now, @Param("note") String note);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiIndex i set i.embeddedCount = :count, i.updatedAt = :now where i.analysisId = :id and i.status = com.abhiraj.archyra.ai.index.IndexStatus.INDEXING")
	int setEmbeddedCount(@Param("id") UUID id, @Param("count") int count, @Param("now") Instant now);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from AiIndex i where i.analysisId in :ids")
	int deleteByAnalysisIdIn(@Param("ids") Collection<UUID> ids);
}
