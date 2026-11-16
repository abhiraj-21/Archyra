package com.abhiraj.archyra.graph;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

import lombok.RequiredArgsConstructor;

/** Read side of the code graph. Every query filters on the analysisId. */
@Service
@RequiredArgsConstructor
public class GraphQueryService {

	private final Neo4jClient neo4jClient;

	/** Empty when no graph was written for {@code analysisId}. */
	public Optional<GraphCounts> getCounts(String analysisId) {
		return neo4jClient.query("""
						MATCH (r:Repository {analysisId: $analysisId})
						RETURN r.name AS name,
						       COUNT { MATCH (c:Class {analysisId: $analysisId}) WHERE NOT coalesce(c.external, false) } AS classes,
						       COUNT { MATCH (m:Method {analysisId: $analysisId}) WHERE NOT coalesce(m.external, false) }
						         + COUNT { MATCH (f:Function {analysisId: $analysisId}) WHERE NOT coalesce(f.external, false) } AS methods
						""")
				.bind(analysisId).to("analysisId")
				.fetchAs(GraphCounts.class)
				.mappedBy((typeSystem, record) -> new GraphCounts(
						record.get("name").asString(), record.get("classes").asLong(), record.get("methods").asLong()))
				.first();
	}

	/**
	 * The graph of {@code analysisId} in the React Flow shape. External nodes (types outside the repository)
	 * are never returned, and neither are edges that touch them.
	 *
	 * <p>The filters are independent:
	 * <ul>
	 * <li>{@code nodeType} keeps only nodes of that type;</li>
	 * <li>{@code relationshipType} keeps only edges of that type;</li>
	 * <li>an edge is returned only if both of its ends are returned, so {@code nodeType=CLASS} gives the
	 * classes and the relationships among them, and no edge ever points at a missing node.</li>
	 * </ul>
	 * A null filter means "all". Labels and relationship types come from the enums, never from input, which
	 * is what makes interpolating a label into Cypher safe; values are bound as parameters.
	 */
	public GraphResponse getGraph(String analysisId, NodeType nodeType, RelationshipType relationshipType) {
		List<NodeType> nodeTypes = nodeType == null ? List.of(NodeType.values()) : List.of(nodeType);
		List<String> labels = nodeTypes.stream().map(NodeType::label).toList();
		List<String> relationshipTypes = (relationshipType == null
				? Arrays.stream(RelationshipType.values()) : Stream.of(relationshipType))
				.map(RelationshipType::name).toList();

		// One query per label: a label-less MATCH cannot use the (analysisId, nodeId) indexes.
		List<GraphNode> nodes = new ArrayList<>();
		List<GraphEdge> edges = new ArrayList<>();
		for (NodeType type : nodeTypes) {
			nodes.addAll(neo4jClient.query("""
							MATCH (n:%s {analysisId: $analysisId})
							WHERE NOT coalesce(n.external, false)
							RETURN n.nodeId AS id, n.name AS name, n.file AS file
							ORDER BY id
							""".formatted(type.label()))
					.bind(analysisId).to("analysisId")
					.fetchAs(GraphNode.class)
					.mappedBy((typeSystem, record) -> new GraphNode(
							record.get("id").asString(), type, record.get("name").asString(null),
							record.get("file").asString(null)))
					.all());

			edges.addAll(neo4jClient.query("""
							MATCH (a:%s {analysisId: $analysisId})-[r]->(b {analysisId: $analysisId})
							WHERE type(r) IN $relationshipTypes
							  AND NOT coalesce(a.external, false) AND NOT coalesce(b.external, false)
							  AND any(label IN labels(b) WHERE label IN $labels)
							RETURN a.nodeId AS source, b.nodeId AS target, type(r) AS type
							ORDER BY source, target, type
							""".formatted(type.label()))
					.bind(analysisId).to("analysisId")
					.bind(relationshipTypes).to("relationshipTypes")
					.bind(labels).to("labels")
					.fetchAs(GraphEdge.class)
					.mappedBy((typeSystem, record) -> new GraphEdge(
							record.get("source").asString(), record.get("target").asString(),
							RelationshipType.valueOf(record.get("type").asString())))
					.all());
		}
		return new GraphResponse(List.copyOf(nodes), List.copyOf(edges));
	}

	// ---- single node ---------------------------------------------------------------------------

	/** Empty if the analysis has no such node, or it is external (outside the repository). */
	public Optional<NodeResponse> getNode(String analysisId, String nodeId) {
		Optional<NodeType> type = typeOf(nodeId);
		if (type.isEmpty()) {
			return Optional.empty(); // not an id the graph hands out, so there is nothing to look up
		}
		String label = type.get().label();

		// The label comes from the id prefix, so the (analysisId, nodeId) index serves the lookup.
		Optional<NodeResponse> node = neo4jClient.query("""
						MATCH (n:%s {analysisId: $analysisId, nodeId: $nodeId})
						WHERE NOT coalesce(n.external, false)
						RETURN n.name AS name, n.qualifiedName AS qualifiedName, n.file AS file, n.line AS line,
						       n.kind AS kind,
						       head([(p)-[:CONTAINS]->(n) WHERE p.analysisId = $analysisId | p.nodeId]) AS parentId
						""".formatted(label))
				.bind(analysisId).to("analysisId")
				.bind(nodeId).to("nodeId")
				.fetchAs(NodeResponse.class)
				.mappedBy((typeSystem, record) -> {
					boolean isFile = type.get() == NodeType.FILE;
					String kind = record.get("kind").asString(null);
					return new NodeResponse(nodeId, type.get(), record.get("name").asString(null),
							record.get("qualifiedName").asString(null), record.get("file").asString(null),
							record.get("line").isNull() ? null : record.get("line").asInt(),
							isFile ? null : kind, isFile && kind != null ? Language.valueOf(kind).displayName() : null,
							record.get("parentId").asString(null), null);
				})
				.one();
		return node.map(found -> withRelationships(found, label, analysisId));
	}

	private NodeResponse withRelationships(NodeResponse node, String label, String analysisId) {
		var relationships = new NodeResponse.Relationships(
				countRelationships(label, analysisId, node.id(), "(n)-[r]->(m {analysisId: $analysisId})"),
				countRelationships(label, analysisId, node.id(), "(m {analysisId: $analysisId})-[r]->(n)"));
		return new NodeResponse(node.id(), node.type(), node.name(), node.qualifiedName(), node.file(), node.line(),
				node.kind(), node.language(), node.parentId(), relationships);
	}

	/** {@code pattern} is a fixed string from this class, never input. */
	private Map<RelationshipType, Long> countRelationships(String label, String analysisId, String nodeId, String pattern) {
		Map<RelationshipType, Long> counts = new EnumMap<>(RelationshipType.class);
		neo4jClient.query("""
						MATCH (n:%s {analysisId: $analysisId, nodeId: $nodeId})
						MATCH %s
						WHERE NOT coalesce(m.external, false)
						RETURN type(r) AS type, count(r) AS count
						""".formatted(label, pattern))
				.bind(analysisId).to("analysisId")
				.bind(nodeId).to("nodeId")
				.fetchAs(RelationshipType.class)
				.mappedBy((typeSystem, record) -> {
					RelationshipType type = RelationshipType.valueOf(record.get("type").asString());
					counts.put(type, record.get("count").asLong());
					return type;
				})
				.all();
		return counts;
	}

	/** Node ids start with their type in lower case: {@code class:}, {@code method:}, {@code file:}, ... */
	private static Optional<NodeType> typeOf(String nodeId) {
		int colon = nodeId.indexOf(':');
		if (colon <= 0) {
			return Optional.empty();
		}
		String prefix = nodeId.substring(0, colon);
		return Arrays.stream(NodeType.values()).filter(type -> type.name().toLowerCase().equals(prefix)).findFirst();
	}

	// ---- files ---------------------------------------------------------------------------------

	/**
	 * A page of {@code File} nodes sorted by path. Both filters are optional and combine with AND.
	 *
	 * @param path a repository path without leading or trailing slash: matches the file with exactly that
	 *             path and every file below it, so {@code src/main} does not match {@code src/mainframe}
	 */
	public FilePage getFiles(String analysisId, Language language, String path, int page, int size) {
		List<String> conditions = new ArrayList<>();
		Map<String, Object> parameters = new HashMap<>();
		parameters.put("analysisId", analysisId);
		if (language != null) {
			conditions.add("f.kind = $language"); // a File node's kind is its language
			parameters.put("language", language.name());
		}
		if (path != null) {
			conditions.add("(f.file = $path OR f.file STARTS WITH $pathPrefix)");
			parameters.put("path", path);
			parameters.put("pathPrefix", path + "/");
		}
		String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);

		long total = neo4jClient.query("MATCH (f:File {analysisId: $analysisId}) %s RETURN count(f) AS total".formatted(where))
				.bindAll(parameters)
				.fetchAs(Long.class).mappedBy((typeSystem, record) -> record.get("total").asLong())
				.one().orElse(0L);

		Map<String, Object> pageParameters = new HashMap<>(parameters);
		pageParameters.put("skip", (long) page * size);
		pageParameters.put("limit", (long) size);
		List<FileInfo> files = neo4jClient.query("""
						MATCH (f:File {analysisId: $analysisId}) %s
						RETURN f.nodeId AS id, f.name AS name, f.file AS path, f.kind AS language
						ORDER BY path SKIP $skip LIMIT $limit
						""".formatted(where))
				.bindAll(pageParameters)
				.fetchAs(FileInfo.class)
				.mappedBy((typeSystem, record) -> new FileInfo(record.get("id").asString(), record.get("name").asString(),
						record.get("path").asString(), Language.valueOf(record.get("language").asString())))
				.all().stream().toList();
		return new FilePage(files, total);
	}
}
