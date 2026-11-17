package com.abhiraj.archyra.analysis;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AnalysisMetadataRepository extends JpaRepository<AnalysisMetadata, UUID> {

	List<AnalysisMetadata> findByAnalysisId(UUID analysisId);

	List<AnalysisMetadata> findByAnalysisIdAndKind(UUID analysisId, MetadataKind kind);

	@Transactional
	@Modifying
	@Query("delete from AnalysisMetadata m where m.analysisId = :analysisId")
	void deleteByAnalysisId(@Param("analysisId") UUID analysisId);
}
