package com.abhiraj.archyra.ai.index;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * All SQL on {@code code_chunks}: batched inserts, the vector column, deletes. Plain SQL on purpose (see
 * {@link CodeChunk}). The analysis id is part of every statement. Never logs chunk text.
 */
@Component
@RequiredArgsConstructor
class ChunkStore {

	private static final int INSERT_BATCH = 500;

	/** A chunk waiting for its vector. */
	record Pending(UUID id, String nodeId, String content) {
	}

	private final JdbcTemplate jdbc;

	@Transactional
	void insertAll(UUID analysisId, List<ChunkData> chunks) {
		jdbc.batchUpdate("""
				INSERT INTO code_chunks (id, analysis_id, node_id, kind, file, start_line, end_line, content)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", chunks, INSERT_BATCH, (PreparedStatement statement, ChunkData chunk) -> {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, analysisId);
			statement.setString(3, chunk.nodeId());
			statement.setString(4, chunk.kind().name());
			statement.setString(5, chunk.file());
			statement.setInt(6, chunk.startLine());
			statement.setInt(7, chunk.endLine());
			statement.setString(8, chunk.content());
		});
	}

	/** The next chunks of the analysis that have no vector yet, in a stable order. */
	List<Pending> nextUnembedded(UUID analysisId, int limit) {
		return jdbc.query("""
				SELECT id, node_id, content FROM code_chunks
				WHERE analysis_id = ? AND embedding IS NULL
				ORDER BY file, start_line, id LIMIT ?
				""", (rs, row) -> new Pending(rs.getObject("id", UUID.class), rs.getString("node_id"), rs.getString("content")),
				analysisId, limit);
	}

	/** Stores one vector per chunk, all in one transaction (a batch is committed or not at all). */
	@Transactional
	void storeEmbeddings(List<Pending> chunks, List<float[]> vectors) {
		if (chunks.size() != vectors.size()) {
			throw new IllegalStateException("Got " + vectors.size() + " vectors for " + chunks.size() + " chunks");
		}
		List<Object[]> rows = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			rows.add(new Object[] { literal(vectors.get(i)), chunks.get(i).id() });
		}
		jdbc.batchUpdate("UPDATE code_chunks SET embedding = CAST(? AS vector) WHERE id = ?", rows);
	}

	int count(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ?", Integer.class, analysisId);
	}

	int countEmbedded(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ? AND embedding IS NOT NULL",
				Integer.class, analysisId);
	}

	@Transactional
	void deleteByAnalysis(UUID analysisId) {
		jdbc.update("DELETE FROM code_chunks WHERE analysis_id = ?", analysisId);
	}

	/** pgvector's text form: {@code [0.1,0.2,...]}. */
	static String literal(float[] vector) {
		StringBuilder text = new StringBuilder(vector.length * 10).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				text.append(',');
			}
			text.append(vector[i]);
		}
		return text.append(']').toString();
	}

	/**
	 * What is embedded for a chunk: the qualified name (taken from the node id, which already holds it) and the first
	 * characters of the text. MiniLM-class models read about 256 word pieces, so more would be cut anyway; the full
	 * text stays in {@code content} for the prompt.
	 */
	static String embeddingInput(String nodeId, String content) {
		int colon = nodeId.indexOf(':');
		String name = colon >= 0 ? nodeId.substring(colon + 1) : nodeId;
		String head = content.length() <= EMBEDDING_TEXT_CHARS ? content : content.substring(0, EMBEDDING_TEXT_CHARS);
		return name + "\n" + head;
	}

	static final int EMBEDDING_TEXT_CHARS = 600;
}
