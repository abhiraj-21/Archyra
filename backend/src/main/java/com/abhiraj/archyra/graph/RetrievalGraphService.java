package com.abhiraj.archyra.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.neo4j.driver.Record;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.common.TestPathClassifier;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

import lombok.RequiredArgsConstructor;

/**
 * Graph reads for Ask AI retrieval and grounding. Everything here is filtered on the {@code analysisId}, never returns
 * external nodes, and drops test code (classified from the stored {@code file} path through {@link TestPathClassifier},
 * like every endpoint that returns graph nodes). Cypher is built only from fixed strings and the label enums; names,
 * ids and limits are bound parameters. Neo4j internal ids never leave this class.
 */
@Service
@RequiredArgsConstructor
public class RetrievalGraphService {

	/** The types of node a question can name or an answer can cite. Repository and module nodes carry no code. */
	private static final List<NodeType> DECLARED = List.of(NodeType.CLASS, NodeType.INTERFACE, NodeType.METHOD,
			NodeType.FUNCTION, NodeType.FILE);

	/** Candidate rows are over-fetched because test nodes are dropped in Java afterwards. */
	private static final int OVERFETCH = 4;

	private static final String NODE_PROPERTIES = """
			%1$s.nodeId AS id, %1$s.name AS name, %1$s.qualifiedName AS qualifiedName, %1$s.file AS file,
			%1$s.line AS line, %1$s.endLine AS endLine,
			head([(p)-[:CONTAINS]->(%1$s) WHERE p.analysisId = $analysisId | p.nodeId]) AS parentId""";

	private final Neo4jClient neo4jClient;

	/**
	 * Declared nodes whose {@code qualifiedName} or {@code name} equals one of {@code names}, or (files) whose path ends
	 * with one. Ordered by the position of the name in {@code names}, then exact qualified name before exact name before
	 * path suffix, then type, then qualified name, so the result is stable. At most {@code limit} nodes.
	 */
	public List<NodeInfo> findByNames(String analysisId, List<String> names, int limit) {
		if (names.isEmpty() || limit <= 0) {
			return List.of();
		}
		record Hit(int nameIndex, int rank, NodeInfo node) {
		}
		List<Hit> hits = new ArrayList<>();
		for (NodeType type : DECLARED) {
			boolean file = type == NodeType.FILE;
			String match = file
					? "n.file = q OR n.file ENDS WITH ('/' + q) OR n.name = q"
					: "n.qualifiedName = q OR n.name = q";
			String rank = file
					? "CASE WHEN n.file = q THEN 0 WHEN n.name = q THEN 1 ELSE 2 END"
					: "CASE WHEN n.qualifiedName = q THEN 0 ELSE 1 END";
			neo4jClient.query("""
							UNWIND range(0, size($names) - 1) AS i
							WITH i, $names[i] AS q
							MATCH (n:%s {analysisId: $analysisId})
							WHERE NOT coalesce(n.external, false) AND (%s)
							RETURN i AS nameIndex, %s AS rank, %s
							LIMIT $fetch
							""".formatted(type.label(), match, rank, NODE_PROPERTIES.formatted("n")))
					.bind(analysisId).to("analysisId")
					.bind(names).to("names")
					.bind(limit * OVERFETCH + 8).to("fetch")
					.fetchAs(Hit.class)
					.mappedBy((typeSystem, record) -> new Hit(record.get("nameIndex").asInt(), record.get("rank").asInt(),
							node(type, record)))
					.all()
					.forEach(hits::add);
		}
		Map<String, Hit> best = new LinkedHashMap<>();
		hits.stream()
				.filter(hit -> !TestPathClassifier.isTestPath(hit.node().file()))
				.sorted(Comparator.comparingInt(Hit::nameIndex).thenComparingInt(Hit::rank)
						.thenComparingInt(hit -> DECLARED.indexOf(hit.node().type()))
						.thenComparing(hit -> hit.node().qualifiedName(), Comparator.nullsLast(Comparator.naturalOrder()))
						.thenComparing(hit -> hit.node().id()))
				.forEach(hit -> best.putIfAbsent(hit.node().id(), hit));
		return best.values().stream().limit(limit).map(Hit::node).toList();
	}

	/**
	 * The nodes directly contained in {@code containerId} (the methods of a type, the types and functions of a file) of
	 * the given types, in source order, test code excluded. At most {@code limit}.
	 */
	public List<NodeInfo> membersOf(String analysisId, String containerId, Set<NodeType> memberTypes, int limit) {
		Optional<NodeType> containerType = typeOf(containerId);
		if (containerType.isEmpty() || limit <= 0) {
			return List.of();
		}
		List<String> labels = memberTypes.stream().map(NodeType::label).sorted().toList();
		List<NodeInfo> members = new ArrayList<>();
		neo4jClient.query("""
						MATCH (c:%s {analysisId: $analysisId, nodeId: $containerId})-[:CONTAINS]->(n)
						WHERE n.analysisId = $analysisId AND NOT coalesce(n.external, false) AND labels(n)[0] IN $labels
						RETURN labels(n)[0] AS label, %s
						ORDER BY coalesce(n.line, 0), n.nodeId
						LIMIT $fetch
						""".formatted(containerType.get().label(), NODE_PROPERTIES.formatted("n")))
				.bind(analysisId).to("analysisId")
				.bind(containerId).to("containerId")
				.bind(labels).to("labels")
				.bind(limit * OVERFETCH + 8).to("fetch")
				.fetchAs(NodeInfo.class)
				.mappedBy((typeSystem, record) -> node(typeFromLabel(record.get("label").asString()), record))
				.all()
				.stream()
				.filter(node -> !TestPathClassifier.isTestPath(node.file()))
				.limit(limit)
				.forEach(members::add);
		return members;
	}

	/** Details of the given nodes (those that exist in this analysis, are declared and are not test code). */
	public Map<String, NodeInfo> nodesById(String analysisId, Collection<String> ids) {
		Map<String, NodeInfo> found = new HashMap<>();
		for (Map.Entry<NodeType, List<String>> group : groupByType(ids).entrySet()) {
			neo4jClient.query("""
							MATCH (n:%s {analysisId: $analysisId})
							WHERE n.nodeId IN $ids AND NOT coalesce(n.external, false)
							RETURN %s
							""".formatted(group.getKey().label(), NODE_PROPERTIES.formatted("n")))
					.bind(analysisId).to("analysisId")
					.bind(group.getValue()).to("ids")
					.fetchAs(NodeInfo.class)
					.mappedBy((typeSystem, record) -> node(group.getKey(), record))
					.all()
					.stream()
					.filter(node -> !TestPathClassifier.isTestPath(node.file()))
					.forEach(node -> found.put(node.id(), node));
		}
		return found;
	}

	/** Of the given ids, those that are nodes of this analysis (declared, not external). Used to verify every cited source. */
	public Set<String> existingNodeIds(String analysisId, Collection<String> ids) {
		Set<String> existing = new LinkedHashSet<>();
		for (Map.Entry<NodeType, List<String>> group : groupByType(ids).entrySet()) {
			neo4jClient.query("""
							MATCH (n:%s {analysisId: $analysisId})
							WHERE n.nodeId IN $ids AND NOT coalesce(n.external, false)
							RETURN n.nodeId AS id
							""".formatted(group.getKey().label()))
					.bind(analysisId).to("analysisId")
					.bind(group.getValue()).to("ids")
					.fetchAs(String.class)
					.mappedBy((typeSystem, record) -> record.get("id").asString())
					.all()
					.forEach(existing::add);
		}
		return existing;
	}

	/**
	 * One hop through {@code DEPENDS_ON}, {@code CALLS}, {@code EXTENDS} and {@code IMPLEMENTS}, in both directions, for
	 * each seed: at most {@code perNode} neighbours, architectural relationships first, then by name. Neighbours that are
	 * external or test code are not counted, so a test that depends on a class never shows up as one of its dependents.
	 */
	public Neighborhood neighbors(String analysisId, Collection<String> seedIds, int perNode) {
		if (seedIds.isEmpty() || perNode <= 0) {
			return Neighborhood.empty();
		}
		List<NeighborEdge> edges = new ArrayList<>();
		Map<String, Integer> totals = new HashMap<>();
		for (Map.Entry<NodeType, List<String>> group : groupByType(seedIds).entrySet()) {
			Map<String, List<NeighborEdge>> bySeed = new LinkedHashMap<>();
			neo4jClient.query("""
							MATCH (n:%s {analysisId: $analysisId})
							WHERE n.nodeId IN $ids AND NOT coalesce(n.external, false)
							CALL {
							  WITH n
							  MATCH (n)-[r:DEPENDS_ON|CALLS|EXTENDS|IMPLEMENTS]-(m)
							  WHERE m.analysisId = $analysisId AND NOT coalesce(m.external, false) AND m <> n
							  RETURN r, m
							  ORDER BY CASE type(r) WHEN 'DEPENDS_ON' THEN 0 WHEN 'EXTENDS' THEN 1 WHEN 'IMPLEMENTS' THEN 2 ELSE 3 END,
							           m.qualifiedName, m.nodeId, startNode(r).nodeId
							  LIMIT $fetch
							}
							RETURN n.nodeId AS seed, type(r) AS type, startNode(r).nodeId AS source, endNode(r).nodeId AS target,
							       labels(m)[0] AS label, %s
							""".formatted(group.getKey().label(), NODE_PROPERTIES.formatted("m")))
					.bind(analysisId).to("analysisId")
					.bind(group.getValue()).to("ids")
					.bind(Math.max(perNode * OVERFETCH, 64)).to("fetch")
					.fetchAs(NeighborEdge.class)
					.mappedBy((typeSystem, record) -> new NeighborEdge(record.get("seed").asString(),
							RelationshipType.valueOf(record.get("type").asString()), record.get("source").asString(),
							record.get("target").asString(), node(typeFromLabel(record.get("label").asString()), record)))
					.all()
					.stream()
					.filter(edge -> !TestPathClassifier.isTestPath(edge.other().file()))
					.forEach(edge -> bySeed.computeIfAbsent(edge.seedId(), key -> new ArrayList<>()).add(edge));
			bySeed.forEach((seed, seedEdges) -> {
				totals.put(seed, seedEdges.size());
				edges.addAll(seedEdges.subList(0, Math.min(perNode, seedEdges.size())));
			});
		}
		return new Neighborhood(edges, totals);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static NodeInfo node(NodeType type, Record record) {
		return new NodeInfo(record.get("id").asString(), type, record.get("name").asString(null),
				record.get("qualifiedName").asString(null), record.get("file").asString(null),
				record.get("line").isNull() ? null : record.get("line").asInt(),
				record.get("endLine").isNull() ? null : record.get("endLine").asInt(),
				record.get("parentId").asString(null));
	}

	/** Seeds grouped by the node type their id says, so every query names one label and uses the (analysisId, nodeId) index. */
	private static Map<NodeType, List<String>> groupByType(Collection<String> ids) {
		Map<NodeType, List<String>> groups = new EnumMap<>(NodeType.class);
		for (String id : new LinkedHashSet<>(ids)) {
			typeOf(id).filter(DECLARED::contains).ifPresent(type -> groups.computeIfAbsent(type, key -> new ArrayList<>()).add(id));
		}
		return groups;
	}

	/** Node ids start with their type in lower case: {@code class:}, {@code method:}, {@code file:}, ... */
	static Optional<NodeType> typeOf(String nodeId) {
		int colon = nodeId.indexOf(':');
		if (colon <= 0) {
			return Optional.empty();
		}
		String prefix = nodeId.substring(0, colon);
		for (NodeType type : NodeType.values()) {
			if (type.name().toLowerCase().equals(prefix)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}

	private static NodeType typeFromLabel(String label) {
		return NodeType.valueOf(label.toUpperCase());
	}
}
