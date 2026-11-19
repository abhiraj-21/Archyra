package com.abhiraj.archyra.ai.index;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Reads chunks for retrieval: the nearest ones to a query vector, and the ones of given graph nodes. Every statement is
 * limited to one analysis, binds all values and has a stable order, so retrieval is deterministic.
 *
 * <p>The vector search is an exact cosine scan ({@code <=>}) of the one analysis; there is no vector index in V1 (see
 * {@link CodeChunk}). Never logs chunk text.
 */
@Component
@RequiredArgsConstructor
public class ChunkSearch {

	private static final RowMapper<FoundChunk> CHUNK = (rs, row) -> new FoundChunk(rs.getString("node_id"),
			ChunkKind.valueOf(rs.getString("kind")), rs.getString("file"), rs.getInt("start_line"), rs.getInt("end_line"),
			rs.getString("content"), rs.getDouble("similarity"));

	private final JdbcTemplate jdbc;

	/** The {@code k} embedded chunks of the analysis closest to {@code query}, most similar first. */
	public List<FoundChunk> nearest(UUID analysisId, float[] query, int k) {
		String vector = ChunkStore.literal(query);
		return jdbc.query("""
				SELECT node_id, kind, file, start_line, end_line, content,
				       1 - (embedding <=> CAST(? AS vector)) AS similarity
				FROM code_chunks
				WHERE analysis_id = ? AND embedding IS NOT NULL
				ORDER BY embedding <=> CAST(? AS vector), file, start_line, node_id
				LIMIT ?
				""", CHUNK, vector, analysisId, vector, k);
	}

	/** The chunks of the given nodes, grouped by node id, each group in source order. Nodes without a chunk are absent. */
	public Map<String, List<FoundChunk>> forNodes(UUID analysisId, Collection<String> nodeIds) {
		if (nodeIds.isEmpty()) {
			return Map.of();
		}
		String[] ids = nodeIds.stream().distinct().toArray(String[]::new);
		List<FoundChunk> chunks = jdbc.query("""
				SELECT node_id, kind, file, start_line, end_line, content, 0.0 AS similarity
				FROM code_chunks
				WHERE analysis_id = ? AND node_id = ANY (?)
				ORDER BY file, start_line, node_id
				""", ps -> {
			ps.setObject(1, analysisId);
			ps.setArray(2, ps.getConnection().createArrayOf("text", ids));
		}, CHUNK);
		Map<String, List<FoundChunk>> byNode = new LinkedHashMap<>();
		chunks.forEach(chunk -> byNode.computeIfAbsent(chunk.nodeId(), key -> new java.util.ArrayList<>()).add(chunk));
		return byNode;
	}
}
