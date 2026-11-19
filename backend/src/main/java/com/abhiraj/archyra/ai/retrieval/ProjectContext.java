package com.abhiraj.archyra.ai.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.analysis.AnalysisMetadataService;
import com.abhiraj.archyra.analysis.AnalysisMetadataService.TechnologyStat;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.analysis.LanguageStat;
import com.abhiraj.archyra.graph.GraphCounts;
import com.abhiraj.archyra.graph.GraphQueryService;
import com.abhiraj.archyra.graph.RetrievalGraphService;
import com.abhiraj.archyra.graph.RetrievalGraphService.DependedOn;
import com.abhiraj.archyra.graph.RetrievalGraphService.ModuleSize;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.RequiredArgsConstructor;

/**
 * The project context pack: what to tell the model about a repository when the question is about the repository as a whole
 * ("what is this project about?"), and the one- or two-line preamble that every question gets. Built from public services
 * only (the analysis metadata, the graph counts and two fixed read queries in {@link RetrievalGraphService}), per analysis
 * and cached: it cannot change once the analysis is COMPLETED, and it is dropped when the analysis is deleted.
 *
 * <p>Each fact is a {@link ProjectItem} that cites the node it describes (the repository node, a module, a class), so the
 * model's citation becomes a source that is verified in Neo4j like any other. Never logs names or counts.
 */
@Service
@RequiredArgsConstructor
public class ProjectContext {

	static final int MAX_MODULES = 12;
	static final int MAX_TOP_TYPES = 8;
	/** Names shown in the identity and preamble lines, so those never grow past a few dozen tokens. */
	private static final int MAX_LANGUAGES_IN_IDENTITY = 6;
	private static final int MAX_TECHNOLOGIES_IN_PREAMBLE = 4;

	private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9 ._:/+#()-]");

	private final AnalysisService analyses;
	private final AnalysisMetadataService metadata;
	private final GraphQueryService counts;
	private final RetrievalGraphService graph;

	private final Cache<UUID, ProjectFacts> cache = Caffeine.newBuilder().maximumSize(200).expireAfterAccess(Duration.ofHours(1)).build();

	/** Empty when the analysis has no graph (nothing to say). */
	public Optional<ProjectFacts> facts(UUID analysisId) {
		ProjectFacts cached = cache.getIfPresent(analysisId);
		if (cached != null) {
			return Optional.of(cached);
		}
		Optional<ProjectFacts> built = build(analysisId);
		built.ifPresent(facts -> cache.put(analysisId, facts));
		return built;
	}

	/** Called when the analysis is deleted or fails. */
	public void evict(UUID analysisId) {
		cache.invalidate(analysisId);
	}

	private Optional<ProjectFacts> build(UUID analysisId) {
		String key = analysisId.toString();
		Optional<GraphCounts> found = counts.getCounts(key);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		GraphCounts totals = found.get();
		List<LanguageStat> languages = metadata.getLanguages(analysisId);
		return Optional.of(new ProjectFacts("repository:" + totals.repositoryName(), totals.repositoryName(),
				analyses.repositoryUrlOf(analysisId).orElse(null), languages, metadata.getTechnologies(analysisId),
				languages.stream().mapToLong(LanguageStat::files).sum(), totals.classes(), totals.methods(),
				graph.modulesBySize(key, MAX_MODULES), graph.mostDependedOn(key, MAX_TOP_TYPES)));
	}

	// ---- the pack ------------------------------------------------------------------------------

	/**
	 * The facts, in rank order: the identity line first (never dropped), then technologies, counts, the most depended-on
	 * classes and the biggest modules. Within a budget the last ones go first (see {@link RetrievedContext#within}).
	 */
	public List<ProjectItem> pack(ProjectFacts facts) {
		List<String> repository = List.of(facts.repositoryNodeId());
		List<ProjectItem> items = new ArrayList<>();
		items.add(new ProjectItem(ProjectItem.Role.IDENTITY, identity(facts), repository));
		String technologies = technologies(facts);
		if (!technologies.isEmpty()) {
			items.add(new ProjectItem(ProjectItem.Role.TECHNOLOGIES, "Detected technologies: " + technologies + ".", repository));
		}
		items.add(new ProjectItem(ProjectItem.Role.COUNTS, "The repository has %d files in all, %d classes and %d methods or functions of its own code."
				.formatted(facts.files(), facts.classes(), facts.methods()), repository));
		for (DependedOn type : facts.topTypes()) {
			items.add(new ProjectItem(ProjectItem.Role.CLASS, "%s is depended on by %d other %s of the repository."
					.formatted(safe(type.name()), type.dependents(), type.dependents() == 1 ? "class or interface" : "classes and interfaces"), List.of(type.id())));
		}
		for (ModuleSize module : facts.modules()) {
			items.add(new ProjectItem(ProjectItem.Role.MODULE, "Module %s holds %d files and %d classes and interfaces."
					.formatted(safe(module.name()), module.files(), module.types()), List.of(module.id())));
		}
		return items;
	}

	private static String identity(ProjectFacts facts) {
		StringBuilder text = new StringBuilder("This repository is ").append(safe(facts.name()));
		if (facts.url() != null) {
			text.append(" (").append(safe(facts.url())).append(')');
		}
		text.append('.');
		if (!facts.languages().isEmpty()) {
			text.append(" Languages by number of files: ").append(facts.languages().stream().limit(MAX_LANGUAGES_IN_IDENTITY)
					.map(language -> safe(language.name()) + " " + language.files()).collect(Collectors.joining(", "))).append('.');
		}
		return text.toString();
	}

	/** {@code Backend framework: Spring Boot, Spring Data; Build tool: Maven}. */
	private static String technologies(ProjectFacts facts) {
		Map<String, List<String>> byCategory = new LinkedHashMap<>();
		for (TechnologyStat technology : facts.technologies()) {
			byCategory.computeIfAbsent(humanize(technology.category()), key -> new ArrayList<>()).add(safe(technology.name()));
		}
		return byCategory.entrySet().stream().map(entry -> entry.getKey() + ": " + String.join(", ", entry.getValue()))
				.collect(Collectors.joining("; "));
	}

	// ---- the preamble --------------------------------------------------------------------------

	/**
	 * One or two lines for every question, so that a specific answer knows the project's stack. Not numbered, so it cannot be
	 * cited, and short enough for the smallest provider budget. Empty when there is nothing to say.
	 */
	public String preamble(UUID analysisId) {
		return facts(analysisId).map(ProjectContext::preamble).orElse("");
	}

	static String preamble(ProjectFacts facts) {
		StringBuilder line = new StringBuilder("This repository is ").append(safe(facts.name()));
		List<String> languages = facts.languages().stream().limit(MAX_LANGUAGES_IN_IDENTITY).map(language -> safe(language.name())).toList();
		if (!languages.isEmpty()) {
			line.append(": ").append(joinWithAnd(languages));
		}
		List<String> technologies = facts.technologies().stream().map(technology -> safe(technology.name())).distinct()
				.limit(MAX_TECHNOLOGIES_IN_PREAMBLE).toList();
		if (!technologies.isEmpty()) {
			line.append(languages.isEmpty() ? ": " : ", ").append(String.join(", ", technologies));
		}
		return line.append('.').toString();
	}

	private static String joinWithAnd(List<String> words) {
		return words.size() == 1 ? words.getFirst()
				: String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
	}

	// ---- text ----------------------------------------------------------------------------------

	/** Names come from the repository: keep a printable, bounded token so nothing in them can read as an instruction. */
	private static String safe(String text) {
		String clean = SAFE.matcher(text == null ? "" : text).replaceAll("").strip();
		return clean.length() > 120 ? clean.substring(0, 120) : clean;
	}

	private static String humanize(String category) {
		String words = category.toLowerCase(Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}
}
