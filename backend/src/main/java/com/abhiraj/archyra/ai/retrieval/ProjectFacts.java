package com.abhiraj.archyra.ai.retrieval;

import java.util.List;

import com.abhiraj.archyra.analysis.AnalysisMetadataService.TechnologyStat;
import com.abhiraj.archyra.analysis.LanguageStat;
import com.abhiraj.archyra.graph.RetrievalGraphService.DependedOn;
import com.abhiraj.archyra.graph.RetrievalGraphService.ModuleSize;

/**
 * What the analysis already knows about the repository as a whole, read once per analysis (it cannot change after
 * COMPLETED). Nothing here is invented: every field is a stored or counted fact.
 *
 * @param repositoryNodeId {@code repository:<name>}, the node the identity facts cite
 * @param url              the repository URL (null if the analysis row is gone)
 * @param languages        languages with file counts, most files first
 * @param technologies     detected technologies, by category then name
 * @param files            files in every language found
 * @param classes          {@code Class} nodes of the repository's own code
 * @param methods          methods and functions of the repository's own code
 * @param modules          up to 12 modules with the most non-test files
 * @param topTypes         up to 8 classes and interfaces that the most others depend on
 */
public record ProjectFacts(String repositoryNodeId, String name, String url, List<LanguageStat> languages,
		List<TechnologyStat> technologies, long files, long classes, long methods, List<ModuleSize> modules,
		List<DependedOn> topTypes) {

	public ProjectFacts {
		languages = List.copyOf(languages);
		technologies = List.copyOf(technologies);
		modules = List.copyOf(modules);
		topTypes = List.copyOf(topTypes);
	}
}
