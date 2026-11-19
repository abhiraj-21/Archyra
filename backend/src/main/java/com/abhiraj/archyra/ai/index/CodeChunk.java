package com.abhiraj.archyra.ai.index;

import java.util.UUID;

import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A piece of working code of one analysis ({@code code_chunks}): the text the model is shown and, once embedded, its
 * vector. Plain analysis id and node id, no foreign keys (the analysis belongs to another feature, the node to Neo4j).
 *
 * <p>This entity defines the table for {@code ddl-auto=update}. Chunks are written, read and embedded with
 * {@link ChunkStore} (plain SQL: batched inserts, the {@code <=>} cosine search and the vector column), never loaded as
 * entities, so the vectors are not pulled into memory by accident. There is deliberately no vector index in V1: an
 * analysis has at most a few ten thousand chunks and every query filters to one analysis, so an exact scan of that
 * analysis is fast and has no filtered-ANN recall problem.
 */
@Entity
@Table(name = "code_chunks", indexes = {
		@Index(name = "idx_chunk_analysis", columnList = "analysis_id"),
		@Index(name = "idx_chunk_analysis_node", columnList = "analysis_id, node_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CodeChunk {

	@Id
	private UUID id;

	@Column(name = "analysis_id", nullable = false, updatable = false)
	private UUID analysisId;

	/** The graph nodeId this chunk belongs to. */
	@Column(name = "node_id", nullable = false, updatable = false, length = 1000)
	private String nodeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private ChunkKind kind;

	@Column(nullable = false, updatable = false, length = 1000)
	private String file;

	@Column(name = "start_line", nullable = false, updatable = false)
	private int startLine;

	@Column(name = "end_line", nullable = false, updatable = false)
	private int endLine;

	@Column(nullable = false, updatable = false, columnDefinition = "text")
	private String content;

	/** Null until embedded. Written only by {@link ChunkStore}. */
	@JdbcTypeCode(SqlTypes.VECTOR)
	@Array(length = EmbeddingSpec.DIMENSION)
	@Column(name = "embedding", insertable = false, updatable = false)
	private float[] embedding;
}
