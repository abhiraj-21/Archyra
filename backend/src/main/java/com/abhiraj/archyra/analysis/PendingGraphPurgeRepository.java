package com.abhiraj.archyra.analysis;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PendingGraphPurgeRepository extends JpaRepository<PendingGraphPurge, UUID> {

	@Modifying
	@Query("update PendingGraphPurge p set p.attempts = p.attempts + 1 where p.analysisId = :id")
	int countAttempt(@Param("id") UUID id);
}
