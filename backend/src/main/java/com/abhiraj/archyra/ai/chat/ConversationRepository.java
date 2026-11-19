package com.abhiraj.archyra.ai.chat;

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
import org.springframework.transaction.annotation.Transactional;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);

	Page<Conversation> findByAnalysisIdAndUserId(UUID analysisId, UUID userId, Pageable pageable);

	long countByAnalysisIdAndUserId(UUID analysisId, UUID userId);

	List<Conversation> findByAnalysisId(UUID analysisId);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Conversation c where c.analysisId = :analysisId")
	int deleteByAnalysisId(@Param("analysisId") UUID analysisId);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Conversation c where c.id in :ids")
	int deleteAllByIdIn(@Param("ids") Collection<UUID> ids);
}
