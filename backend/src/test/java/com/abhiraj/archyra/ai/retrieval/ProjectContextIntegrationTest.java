package com.abhiraj.archyra.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.abhiraj.archyra.analysis.AnalysisMetadataService;
import com.abhiraj.archyra.common.TestPathClassifier;
import com.abhiraj.archyra.graph.NodeInfo;
import com.abhiraj.archyra.graph.RetrievalGraphService;
import com.abhiraj.archyra.ai.AiIntegrationSupport;
import com.abhiraj.archyra.user.User;

/**
 * The project context pack and the README/manifest chunks over real analyses: real pipeline, Neo4j, pgvector and the real
 * ONNX model. The facts are checked against what the graph and the metadata hold, never against numbers made up here.
 */
class ProjectContextIntegrationTest extends AiIntegrationSupport {

	@Autowired
	ProjectContext project;
	@Autowired
	RetrievalGraphService graph;
	@Autowired
	AnalysisMetadataService metadata;
	@Autowired
	RetrievalService retrieval;

	private UUID readyAnalysis(User user, String url) throws Exception {
		UUID id = analyzeCompleted(user, url);
		awaitIndexStatus(user, id, "READY");
		return id;
	}

	// ---- the facts -----------------------------------------------------------------------------

	@Test
	void theFactsAreWhatTheGraphAndTheMetadataHold() throws Exception {
		User owner = newUser();
		UUID id = readyAnalysis(owner, SHOP);

		ProjectFacts facts = project.facts(id).orElseThrow();

		assertThat(facts.repositoryNodeId()).isEqualTo("repository:" + facts.name());
		assertThat(facts.url()).isEqualTo(SHOP);
		assertThat(facts.languages()).isEqualTo(metadata.getLanguages(id));
		assertThat(facts.languages()).extracting(language -> language.name()).contains("Java", "Python");
		assertThat(facts.files()).isEqualTo(facts.languages().stream().mapToLong(language -> language.files()).sum());
		assertThat(facts.classes()).isEqualTo(neo4jCount(id, "Class"));
		assertThat(facts.methods()).isEqualTo(neo4jCount(id, "Method") + neo4jCount(id, "Function"));
		// the sample shop's packages, each with the non-test files and types it holds
		assertThat(facts.modules()).extracting(module -> module.name()).contains("com.example.shop.web", "com.example.shop.service",
				"com.example.shop.repository", "com.example.shop.model");
		assertThat(facts.modules()).allSatisfy(module -> assertThat(module.files()).isPositive());
		assertThat(facts.modules()).isSortedAccordingTo((a, b) -> b.files() != a.files() ? Integer.compare(b.files(), a.files()) : a.name().compareTo(b.name()));
		// the most depended-on types, recomputed here straight from Neo4j: distinct classes and interfaces with a DEPENDS_ON edge into the type
		List<String> expected = neo4j.query("""
				MATCH (d)-[:DEPENDS_ON]->(c)
				WHERE c.analysisId = $id AND d.analysisId = $id AND (c:Class OR c:Interface) AND (d:Class OR d:Interface)
				  AND NOT coalesce(c.external, false) AND NOT coalesce(d.external, false) AND d <> c
				RETURN c.qualifiedName AS name, count(DISTINCT d) AS dependents
				""").bind(id.toString()).to("id").fetchAs(String.class)
				.mappedBy((type, record) -> String.format("%09d", 999_999_999 - record.get("dependents").asInt()) + record.get("name").asString()).all().stream()
				.sorted().map(row -> row.substring(9)).toList();
		assertThat(facts.topTypes()).extracting(type -> type.qualifiedName())
				.containsExactlyElementsOf(expected.subList(0, Math.min(expected.size(), ProjectContext.MAX_TOP_TYPES)));
		assertThat(facts.topTypes().getFirst().qualifiedName()).startsWith("com.example.shop.model.Order");
		assertThat(facts.topTypes()).extracting(type -> type.dependents()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(facts.topTypes().size()).isLessThanOrEqualTo(ProjectContext.MAX_TOP_TYPES);
		assertThat(facts.modules().size()).isLessThanOrEqualTo(ProjectContext.MAX_MODULES);
	}

	private long neo4jCount(UUID id, String label) {
		return neo4jClient().query("MATCH (n:" + label + " {analysisId: $id}) WHERE NOT coalesce(n.external, false) RETURN count(n) AS c")
				.bind(id.toString()).to("id").fetchAs(Long.class).mappedBy((type, record) -> record.get("c").asLong()).one().orElse(0L);
	}

	@Autowired
	org.springframework.data.neo4j.core.Neo4jClient neo4j;

	private org.springframework.data.neo4j.core.Neo4jClient neo4jClient() {
		return neo4j;
	}

	@Test
	void testCodeAndExternalNodesNeverAppear() throws Exception {
		User owner = newUser();
		UUID id = readyAnalysis(owner, TESTS_AND_MAIN);

		ProjectFacts facts = project.facts(id).orElseThrow();
		List<ProjectItem> pack = project.pack(facts);

		Map<String, NodeInfo> cited = graph.citableNodes(id.toString(), pack.stream().flatMap(item -> item.nodeIds().stream()).toList());
		assertThat(cited.values()).allSatisfy(node -> assertThat(TestPathClassifier.isTestPath(node.file())).as(node.id()).isFalse());
		assertThat(facts.topTypes()).allSatisfy(type -> assertThat(type.id()).doesNotContain("Test").doesNotStartWith("external:"));
		assertThat(facts.modules()).allSatisfy(module -> assertThat(module.id()).doesNotStartWith("external:"));
		// every non-test file of every listed module is counted, a test file is not
		long listedFiles = facts.modules().stream().mapToLong(module -> module.files()).sum();
		long nonTestFiles = neo4j.query("MATCH (f:File {analysisId: $id}) WHERE NOT coalesce(f.external, false) RETURN f.file AS file")
				.bind(id.toString()).to("id").fetchAs(String.class).mappedBy((type, record) -> record.get("file").asString()).all().stream()
				.filter(file -> !TestPathClassifier.isTestPath(file)).count();
		assertThat(listedFiles).isLessThanOrEqualTo(nonTestFiles);
	}

	@Test
	void everyNodeTheFactsCiteExistsAndTheOrderIsStable() throws Exception {
		User owner = newUser();
		UUID id = readyAnalysis(owner, SHOP);
		ProjectFacts facts = project.facts(id).orElseThrow();

		List<ProjectItem> pack = project.pack(facts);

		assertThat(pack.getFirst().role()).isEqualTo(ProjectItem.Role.IDENTITY);
		assertThat(pack.getFirst().nodeIds()).containsExactly(facts.repositoryNodeId());
		assertThat(pack).extracting(ProjectItem::role).isSorted(); // identity, technologies, counts, classes, modules
		List<String> cited = pack.stream().flatMap(item -> item.nodeIds().stream()).distinct().toList();
		assertThat(graph.citableNodes(id.toString(), cited).keySet()).containsExactlyInAnyOrderElementsOf(cited);
		// built again from scratch: the same facts in the same order
		project.evict(id);
		assertThat(project.pack(project.facts(id).orElseThrow())).isEqualTo(pack);
		assertThat(project.preamble(id)).startsWith("This repository is ").contains("Java").hasSizeLessThan(260);
	}

	@Test
	void theFactsAreCachedPerAnalysisAndDroppedWhenItIsDeleted() throws Exception {
		User owner = newUser();
		UUID id = readyAnalysis(owner, SHOP);
		ProjectFacts first = project.facts(id).orElseThrow();
		assertThat(project.facts(id).orElseThrow()).isSameAs(first);

		assertThat(deleteAs(owner, "/api/v1/analyses/" + id).getResponse().getStatus()).isEqualTo(204);

		assertThat(project.facts(id)).isEmpty(); // not served from a cache: the analysis is gone
	}

	// ---- the README and manifest chunks --------------------------------------------------------

	@Test
	void theReadmeAndManifestChunksAreCapturedEmbeddedCountedAndCleanedOfBadges() throws Exception {
		User owner = newUser();
		UUID id = readyAnalysis(owner, SHOP_DOCS);

		List<Map<String, Object>> docs = jdbc.queryForList("SELECT kind, node_id, file, start_line, end_line, content, embedding IS NOT NULL AS embedded FROM code_chunks WHERE analysis_id = ? AND kind IN ('README', 'MANIFEST') ORDER BY kind", id);

		assertThat(docs).extracting(row -> row.get("kind")).containsExactly("MANIFEST", "README");
		assertThat(docs).allSatisfy(row -> {
			assertThat((Boolean) row.get("embedded")).isTrue();
			assertThat((String) row.get("node_id")).startsWith("repository:");
			assertThat((Integer) row.get("start_line")).isEqualTo(1);
		});
		Map<String, Object> readme = docs.get(1);
		assertThat((String) readme.get("file")).isEqualTo("README.md");
		assertThat((String) readme.get("content")).contains("Sample Shop is a tiny order service").doesNotContain("travis").doesNotContain("shields.io")
				.doesNotContain("architecture.png").doesNotContain("<h1").doesNotContain("<img");
		assertThat((Integer) readme.get("end_line")).isEqualTo((int) ((String) readme.get("content")).lines().count());
		assertThat((String) docs.getFirst().get("content")).contains("name: Sample Shop").contains("description: A tiny order service used as a test fixture");
		// they count in the index like any chunk
		Integer chunkCount = jdbc.queryForObject("SELECT chunk_count FROM ai_index WHERE analysis_id = ?", Integer.class, id);
		Integer stored = jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ?", Integer.class, id);
		assertThat(chunkCount).isEqualTo(stored);
	}

	@Test
	void theyAreSearchableLikeAnyChunkButNeverSeedGraphExpansion() throws Exception {
		User owner = newUser();
		UUID id = readyAnalysis(owner, SHOP_DOCS);

		// a specific question the README answers: its chunk can come back as a vector hit, and it brings no graph facts of its own
		List<ContextItem> items = retrieval.retrieve(RetrievalRequest.of(id, "Does this keep orders in memory until the process stops?")).ranked();

		List<CodeItem> docs = items.stream().filter(CodeItem.class::isInstance).map(CodeItem.class::cast)
				.filter(item -> item.nodeId().startsWith("repository:")).toList();
		assertThat(docs).isNotEmpty();
		assertThat(docs).allSatisfy(item -> assertThat(item.priority()).isEqualTo(Priority.VECTOR));
		assertThat(items.stream().filter(FactItem.class::isInstance).map(item -> ((FactItem) item).text()).collect(Collectors.joining("\n")))
				.doesNotContain("repository:").doesNotContain("Sample Shop");
		assertThat(items.stream().filter(item -> item.priority() == Priority.NEIGHBOR || item.priority() == Priority.FACT)
				.flatMap(item -> item.nodeIds().stream())).noneMatch(nodeId -> nodeId.startsWith("repository:"));
	}

	@Test
	void anOverviewQuestionGetsThePackThenTheReadmeThenAtMostFourHits() throws Exception {
		User owner = newUser();
		UUID id = readyAnalysis(owner, SHOP_DOCS);

		RetrievedContext context = retrieval.retrieve(RetrievalRequest.of(id, "What is this project about?"));

		assertThat(context.usable()).isTrue();
		List<ContextItem> ranked = context.ranked();
		assertThat(ranked.getFirst()).isInstanceOfSatisfying(ProjectItem.class, item -> assertThat(item.role()).isEqualTo(ProjectItem.Role.IDENTITY));
		List<Priority> order = ranked.stream().map(ContextItem::priority).toList();
		assertThat(order).isSorted(); // PROJECT, PROJECT_DOCS, then VECTOR: nothing from name lookup, facts, neighbours or sticky
		assertThat(order).doesNotContain(Priority.NAME_MATCH, Priority.FACT, Priority.NEIGHBOR, Priority.STICKY);
		assertThat(ranked.stream().filter(item -> item.priority() == Priority.PROJECT_DOCS)).hasSize(2);
		assertThat(ranked.stream().filter(item -> item.priority() == Priority.VECTOR).count()).isLessThanOrEqualTo(RetrievalService.OVERVIEW_VECTOR_HITS);
		// a README and a manifest chunk is not repeated as a vector hit
		assertThat(ranked.stream().filter(CodeItem.class::isInstance).map(item -> ((CodeItem) item).nodeId() + "@" + ((CodeItem) item).startLine() + "@" + ((CodeItem) item).file()))
				.doesNotHaveDuplicates();
	}

	@Test
	void anIndexFromBeforeTheReadmeWasCapturedStillGetsThePack() throws Exception {
		User owner = newUser();
		UUID id = readyAnalysis(owner, SHOP_DOCS);
		jdbc.update("DELETE FROM code_chunks WHERE analysis_id = ? AND kind IN ('README', 'MANIFEST')", id);

		RetrievedContext context = retrieval.retrieve(RetrievalRequest.of(id, "What is this project about?"));

		assertThat(context.usable()).isTrue();
		assertThat(context.ranked().stream().filter(ProjectItem.class::isInstance)).hasSizeGreaterThan(3);
		assertThat(context.ranked().stream().noneMatch(item -> item.priority() == Priority.PROJECT_DOCS)).isTrue();
	}
}
