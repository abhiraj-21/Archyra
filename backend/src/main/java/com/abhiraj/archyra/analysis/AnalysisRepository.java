package com.abhiraj.archyra.analysis;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

	List<Analysis> findByStatusNotIn(Collection<AnalysisStatus> statuses);

	Page<Analysis> findByOwnerId(UUID ownerId, Pageable pageable);

	/** Rows from before accounts existed. */
	List<Analysis> findByOwnerIdIsNull();
}
