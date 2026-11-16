package com.abhiraj.archyra.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.NodeType;

import lombok.RequiredArgsConstructor;

/**
 * Read side of the code graph for single files and classes: what a file contains, imports and references,
 * and what a class extends, implements, contains and depends on. Every query filters on the analysisId.
 *
 * <p>Only fixed strings are interpolated into Cypher (labels, patterns); ids and the analysisId are bound.
 */
@Service
@RequiredArgsConstructor
public class CodeQueryService {

	private static final String NODE_REF_COLUMNS = """
			labels(t)[0] AS label, t.nodeId AS id, t.name AS name, t.qualifiedName AS qualifiedName,
			t.file AS file, coalesce(t.external, false) AS external""";

	private final Neo4jClient neo4jClient;

	// ---- file ----------------------------------------------------------------------------------

	/** Empty if {@code fileId} is not a file of this analysis. */
	public Optional<FileResponse> getFileDetails(String analysisId, String fileId) {
		if (!fileId.startsWith("file:")) {
			return Optional.empty();
		}
		Map<String, Object> parameters = Map.of("analysisId", analysisId, "nodeId", fileId);

		record Header(String name, String path, Language language, NodeRef module) {
		}
		Optional<Header> header = neo4jClient.query("""
						MATCH (f:File {analysisId: $analysisId, nodeId: $nodeId})
						RETURN f.name AS name, f.file AS path, f.kind AS language,
						       head([(m:Module)-[:CONTAINS]->(f) | m.nodeId]) AS moduleId,
						       head([(m:Module)-[:CONTAINS]->(f) | m.name]) AS moduleName
						""")
				.bindAll(parameters)
				.fetchAs(Header.class)
				.mappedBy((typeSystem, record) -> new Header(record.get("name").asString(), record.get("path").asString(),
						Language.valueOf(record.get("language").asString()),
						record.get("moduleId").isNull() ? null
								: new NodeRef(record.get("moduleId").asString(), NodeType.MODULE,
										record.get("moduleName").asString(), null, null, false)))
				.one();
		if (header.isEmpty()) {
			return Optional.empty();
		}

		List<EntitySummary> declarations = neo4jClient.query("""
						MATCH (f:File {analysisId: $analysisId, nodeId: $nodeId})-[:CONTAINS*1..]->(n {analysisId: $analysisId})
						WHERE any(label IN labels(n) WHERE label IN ['Class', 'Interface', 'Method', 'Function'])
						  AND NOT coalesce(n.external, false)
						RETURN labels(n)[0] AS label, n.nodeId AS id, n.name AS name, n.kind AS kind, n.line AS line,
						       head([(p)-[:CONTAINS]->(n) | p.nodeId]) AS parentId
						ORDER BY coalesce(n.line, 0), id
						""")
				.bindAll(parameters)
				.fetchAs(EntitySummary.class)
				.mappedBy((typeSystem, record) -> entity(record, record.get("parentId").asString(null)))
				.all().stream().toList();

		List<NodeRef> imports = nodeRefs("""
				MATCH (f:File {analysisId: $analysisId, nodeId: $nodeId})-[:IMPORTS]->(t {analysisId: $analysisId})
				RETURN %s
				ORDER BY external, id""", parameters);

		List<NodeRef> references = nodeRefs("""
				MATCH (f:File {analysisId: $analysisId, nodeId: $nodeId})-[:CONTAINS*1..]->(s {analysisId: $analysisId})
				      -[:REFERENCES]->(t {analysisId: $analysisId})
				WHERE t.file IS NULL OR t.file <> f.file
				RETURN DISTINCT %s
				ORDER BY external, id""", parameters);

		Header found = header.get();
		return Optional.of(new FileResponse(fileId, found.name(), found.path(), found.language().displayName(),
				found.module(),
				ofType(declarations, NodeType.CLASS), ofType(declarations, NodeType.INTERFACE),
				ofType(declarations, NodeType.METHOD), ofType(declarations, NodeType.FUNCTION),
				imports, references));
	}

	private static List<EntitySummary> ofType(List<EntitySummary> declarations, NodeType type) {
		return declarations.stream().filter(declaration -> declaration.type() == type).toList();
	}

	// ---- class ---------------------------------------------------------------------------------

	/** Empty if {@code classId} is not a (non-external) {@code Class} node of this analysis. */
	public Optional<ClassResponse> getClassDetails(String analysisId, String classId) {
		if (!classId.startsWith("class:")) {
			return Optional.empty();
		}
		Map<String, Object> parameters = Map.of("analysisId", analysisId, "nodeId", classId);

		record Header(String name, String qualifiedName, String kind, String file, Integer line, String parentId) {
		}
		Optional<Header> header = neo4jClient.query("""
						MATCH (c:Class {analysisId: $analysisId, nodeId: $nodeId})
						WHERE NOT coalesce(c.external, false)
						RETURN c.name AS name, c.qualifiedName AS qualifiedName, c.kind AS kind, c.file AS file,
						       c.line AS line, head([(p)-[:CONTAINS]->(c) | p.nodeId]) AS parentId
						""")
				.bindAll(parameters)
				.fetchAs(Header.class)
				.mappedBy((typeSystem, record) -> new Header(record.get("name").asString(null),
						record.get("qualifiedName").asString(null), record.get("kind").asString(null),
						record.get("file").asString(null), record.get("line").isNull() ? null : record.get("line").asInt(),
						record.get("parentId").asString(null)))
				.one();
		if (header.isEmpty()) {
			return Optional.empty();
		}

		List<NodeRef> superclasses = nodeRefs("""
				MATCH (c:Class {analysisId: $analysisId, nodeId: $nodeId})-[:EXTENDS]->(t {analysisId: $analysisId})
				RETURN %s
				ORDER BY id""", parameters);
		List<NodeRef> interfaces = nodeRefs("""
				MATCH (c:Class {analysisId: $analysisId, nodeId: $nodeId})-[:IMPLEMENTS]->(t {analysisId: $analysisId})
				RETURN %s
				ORDER BY id""", parameters);
		List<NodeRef> dependencies = nodeRefs("""
				MATCH (c:Class {analysisId: $analysisId, nodeId: $nodeId})-[:DEPENDS_ON]->(t {analysisId: $analysisId})
				WHERE NOT coalesce(t.external, false)
				RETURN %s
				ORDER BY id""", parameters);
		List<EntitySummary> methods = neo4jClient.query("""
						MATCH (c:Class {analysisId: $analysisId, nodeId: $nodeId})-[:CONTAINS]->(n:Method {analysisId: $analysisId})
						WHERE NOT coalesce(n.external, false)
						RETURN labels(n)[0] AS label, n.nodeId AS id, n.name AS name, n.kind AS kind, n.line AS line
						ORDER BY coalesce(n.line, 0), id
						""")
				.bindAll(parameters)
				.fetchAs(EntitySummary.class)
				.mappedBy((typeSystem, record) -> entity(record, null))
				.all().stream().toList();

		Header found = header.get();
		return Optional.of(new ClassResponse(classId, found.name(), found.qualifiedName(), found.kind(), found.file(),
				found.line(), found.parentId(), superclasses, methods, interfaces, dependencies));
	}

	// ---- mapping -------------------------------------------------------------------------------

	/** {@code query} must contain one {@code %s} for {@link #NODE_REF_COLUMNS} and name its target {@code t}. */
	private List<NodeRef> nodeRefs(String query, Map<String, Object> parameters) {
		List<NodeRef> refs = new ArrayList<>(neo4jClient.query(query.formatted(NODE_REF_COLUMNS))
				.bindAll(parameters)
				.fetchAs(NodeRef.class)
				.mappedBy((typeSystem, record) -> new NodeRef(record.get("id").asString(), nodeType(record.get("label").asString()),
						record.get("name").asString(null), record.get("qualifiedName").asString(null),
						record.get("file").asString(null), record.get("external").asBoolean()))
				.all());
		return List.copyOf(refs);
	}

	private static EntitySummary entity(org.neo4j.driver.Record record, String parentId) {
		return new EntitySummary(record.get("id").asString(), nodeType(record.get("label").asString()),
				record.get("name").asString(null), record.get("kind").asString(null),
				record.get("line").isNull() ? null : record.get("line").asInt(), parentId);
	}

	private static NodeType nodeType(String label) {
		return NodeType.valueOf(label.toUpperCase());
	}
}
