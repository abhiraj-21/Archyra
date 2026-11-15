package com.abhiraj.archyra.analysis;

import java.util.UUID;

import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.TechnologyIR;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What an analysis learned about a repository besides its code graph: the languages it contains and the
 * technologies it uses ({@code analysis_metadata} table, one row per language or technology). Read by the
 * repository information endpoint.
 */
@Entity
@Table(name = "analysis_metadata", indexes = @Index(name = "idx_analysis_metadata_analysis", columnList = "analysis_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisMetadata {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "analysis_id", nullable = false, updatable = false)
	private UUID analysisId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MetadataKind kind;

	/** Language or technology name, e.g. {@code Java}, {@code Spring Boot}. */
	@Column(nullable = false, length = 100)
	private String name;

	/** Technology category ({@code BACKEND_FRAMEWORK}, ...); null for languages. */
	@Column(length = 40)
	private String category;

	/** Package ecosystem or tool family a technology was found through; null for languages. */
	@Column(length = 40)
	private String ecosystem;

	/** The file that revealed a technology; null for languages. */
	@Column(length = 300)
	private String evidence;

	/** Languages: number of files. */
	@Column(name = "file_count")
	private Integer fileCount;

	/** Languages: whether its code is part of the graph (an extractor exists), or it is only counted. */
	@Column
	private Boolean analysed;

	public static AnalysisMetadata language(UUID analysisId, Language language, int fileCount, boolean analysed) {
		AnalysisMetadata row = new AnalysisMetadata();
		row.analysisId = analysisId;
		row.kind = MetadataKind.LANGUAGE;
		row.name = language.displayName();
		row.fileCount = fileCount;
		row.analysed = analysed;
		return row;
	}

	public static AnalysisMetadata technology(UUID analysisId, TechnologyIR technology) {
		AnalysisMetadata row = new AnalysisMetadata();
		row.analysisId = analysisId;
		row.kind = MetadataKind.TECHNOLOGY;
		row.name = technology.name();
		row.category = technology.category().name();
		row.ecosystem = technology.ecosystem();
		row.evidence = technology.evidence() == null ? null : truncate(technology.evidence(), 300);
		return row;
	}

	private static String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}
}
