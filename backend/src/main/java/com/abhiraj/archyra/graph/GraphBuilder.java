package com.abhiraj.archyra.graph;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.EdgeIR;
import com.abhiraj.archyra.ir.NodeIR;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes a resolved {@link CodeGraphIR} to Neo4j.
 *
 * <p>Every node carries the {@code analysisId} and its stable {@code nodeId}, and every query filters on
 * the analysisId, so graphs of different analyses never connect. Writes are batched
 * ({@code UNWIND $rows ... MERGE}). Labels and relationship types come only from the enums, never from
 * input, which is what makes interpolating them into Cypher safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphBuilder {

	private static final int BATCH_SIZE = 1000;

	private final Neo4jClient neo4jClient;
	private final AtomicBoolean schemaReady = new AtomicBoolean();

	/** Writes all nodes, then all relationships, of {@code graph} under {@code analysisId}. */
	public void build(String analysisId, CodeGraphIR graph) {
		ensureSchema();

		Map<String, NodeType> typeById = new HashMap<>();
		Map<NodeType, List<Map<String, Object>>> nodeRows = new EnumMap<>(NodeType.class);
		for (NodeIR node : graph.nodes()) {
			typeById.put(node.id(), node.type());
			nodeRows.computeIfAbsent(node.type(), key -> new ArrayList<>()).add(nodeRow(node));
		}
		nodeRows.forEach((type, rows) -> writeNodes(analysisId, type, rows));

		Map<EdgeGroup, List<Map<String, Object>>> edgeRows = new LinkedHashMap<>();
		for (EdgeIR edge : graph.edges()) {
			NodeType sourceType = typeById.get(edge.source());
			NodeType targetType = typeById.get(edge.target());
			if (sourceType == null || targetType == null) {
				log.warn("Skipping {} edge with unknown endpoint: {} -> {}", edge.type(), edge.source(), edge.target());
				continue;
			}
			edgeRows.computeIfAbsent(new EdgeGroup(edge.type(), sourceType, targetType), key -> new ArrayList<>())
					.add(Map.of("source", edge.source(), "target", edge.target()));
		}
		edgeRows.forEach((group, rows) -> writeEdges(analysisId, group, rows));

		log.info("Graph for analysis {} written: {} nodes, {} relationships",
				analysisId, graph.nodes().size(), graph.edges().size());
	}

	/** Removes everything written for {@code analysisId}, e.g. after a failed or partial build. */
	public void delete(String analysisId) {
		for (NodeType type : NodeType.values()) {
			neo4jClient.query("MATCH (n:" + type.label() + " {analysisId: $analysisId}) DETACH DELETE n")
					.bind(analysisId).to("analysisId")
					.run();
		}
	}

	// ---- writes ------------------------------------------------------------------------------

	private void writeNodes(String analysisId, NodeType type, List<Map<String, Object>> rows) {
		String cypher = """
				UNWIND $rows AS row
				MERGE (n:%s {analysisId: $analysisId, nodeId: row.nodeId})
				SET n.name = row.name, n.qualifiedName = row.qualifiedName, n.file = row.file,
				    n.line = row.line, n.endLine = row.endLine, n.kind = row.kind, n.external = row.external
				""".formatted(type.label());
		for (List<Map<String, Object>> batch : partition(rows)) {
			neo4jClient.query(cypher)
					.bind(batch).to("rows")
					.bind(analysisId).to("analysisId")
					.run();
		}
	}

	private void writeEdges(String analysisId, EdgeGroup group, List<Map<String, Object>> rows) {
		String cypher = """
				UNWIND $rows AS row
				MATCH (a:%s {analysisId: $analysisId, nodeId: row.source})
				MATCH (b:%s {analysisId: $analysisId, nodeId: row.target})
				MERGE (a)-[:%s]->(b)
				""".formatted(group.sourceType().label(), group.targetType().label(), group.type().name());
		for (List<Map<String, Object>> batch : partition(rows)) {
			neo4jClient.query(cypher)
					.bind(batch).to("rows")
					.bind(analysisId).to("analysisId")
					.run();
		}
	}

	private static Map<String, Object> nodeRow(NodeIR node) {
		Map<String, Object> row = new HashMap<>();
		row.put("nodeId", node.id());
		row.put("name", node.name());
		row.put("qualifiedName", node.qualifiedName());
		row.put("file", node.file());
		row.put("line", node.line());
		row.put("endLine", node.endLine());
		row.put("kind", node.kind());
		row.put("external", node.external());
		return row;
	}

	private static <T> List<List<T>> partition(List<T> rows) {
		List<List<T>> batches = new ArrayList<>();
		for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
			batches.add(rows.subList(from, Math.min(from + BATCH_SIZE, rows.size())));
		}
		return batches;
	}

	/** Indexes make the {@code MERGE}/{@code MATCH} on (analysisId, nodeId) cheap. Created once per run. */
	private void ensureSchema() {
		if (schemaReady.get()) {
			return;
		}
		for (NodeType type : NodeType.values()) {
			neo4jClient.query("CREATE INDEX %s_analysis_node IF NOT EXISTS FOR (n:%s) ON (n.analysisId, n.nodeId)"
					.formatted(type.name().toLowerCase(), type.label())).run();
		}
		schemaReady.set(true);
	}

	private record EdgeGroup(RelationshipType type, NodeType sourceType, NodeType targetType) {
	}
}
