package com.abhiraj.archyra.codebase;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.analysis.AnalysisMetadataService;
import com.abhiraj.archyra.analysis.AnalysisMetadataService.TechnologyStat;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.analysis.LanguageStat;
import com.abhiraj.archyra.graph.GraphCounts;
import com.abhiraj.archyra.graph.GraphQueryService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RepositoryService {

	private final AnalysisService analysisService;
	private final AnalysisMetadataService metadataService;
	private final GraphQueryService graphQueryService;

	/**
	 * Describes the repository of a completed analysis: languages, file count and detected technologies from
	 * PostgreSQL, name and class/method counts from the graph.
	 *
	 * @throws com.abhiraj.archyra.common.AnalysisNotFoundException     if the id is malformed or unknown
	 * @throws com.abhiraj.archyra.common.AnalysisNotCompletedException if the analysis is not {@code COMPLETED}
	 */
	public RepositoryResponse getRepository(String analysisId) {
		UUID id = analysisService.requireCompleted(analysisId);
		List<LanguageStat> languages = metadataService.getLanguages(id);
		List<TechnologyResponse> technologies = metadataService.getTechnologies(id).stream()
				.map(RepositoryService::toResponse)
				.toList();
		GraphCounts counts = graphQueryService.getCounts(id.toString())
				.orElseThrow(() -> new IllegalStateException("Analysis " + id + " is COMPLETED but has no graph"));

		return new RepositoryResponse(
				counts.repositoryName(),
				languages.stream().map(LanguageStat::name).toList(),
				languages.stream().mapToLong(LanguageStat::files).sum(),
				counts.classes(),
				counts.methods(),
				technologies);
	}

	private static TechnologyResponse toResponse(TechnologyStat stat) {
		return new TechnologyResponse(stat.name(), stat.category());
	}
}
