package com.abhiraj.archyra.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Reads what an analysis stored in {@code analysis_metadata}, for features such as the repository
 * information endpoint that must not reach into this package's repositories.
 */
@Service
@RequiredArgsConstructor
public class AnalysisMetadataService {

	private final AnalysisMetadataRepository metadataRepository;

	/** Every language found, whether analysed or only counted, the most used first (ties by name). */
	public List<LanguageStat> getLanguages(UUID analysisId) {
		return metadataRepository.findByAnalysisIdAndKind(analysisId, MetadataKind.LANGUAGE).stream()
				.map(row -> new LanguageStat(row.getName(), row.getFileCount() == null ? 0 : row.getFileCount()))
				.sorted(Comparator.comparingInt(LanguageStat::files).reversed().thenComparing(LanguageStat::name))
				.toList();
	}

	/** A framework, library or tool the repository uses, with its category ({@code BACKEND_FRAMEWORK}, {@code BUILD_TOOL}, ...). */
	public record TechnologyStat(String name, String category) {
	}

	/** The detected technologies, by category and then name, so the order is stable. */
	public List<TechnologyStat> getTechnologies(UUID analysisId) {
		return metadataRepository.findByAnalysisIdAndKind(analysisId, MetadataKind.TECHNOLOGY).stream()
				.map(row -> new TechnologyStat(row.getName(), row.getCategory() == null ? "LIBRARY" : row.getCategory()))
				.sorted(Comparator.comparing(TechnologyStat::category).thenComparing(TechnologyStat::name))
				.toList();
	}
}
