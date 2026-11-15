package com.abhiraj.archyra.analysis;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisMetadataRepository extends JpaRepository<AnalysisMetadata, UUID> {

	List<AnalysisMetadata> findByAnalysisId(UUID analysisId);
}
