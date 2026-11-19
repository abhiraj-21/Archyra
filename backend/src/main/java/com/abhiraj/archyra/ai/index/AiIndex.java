package com.abhiraj.archyra.ai.index;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * The state of the Ask AI index of one analysis ({@code ai_index}). No row means "not indexed". A plain analysis id,
 * no foreign key: the analysis belongs to another feature. Status changes are guarded UPDATEs in
 * {@link AiIndexRepository}, so a late job can never resurrect or overwrite a row that was deleted or already moved on.
 */
@Entity
@DynamicUpdate
@Table(name = "ai_index")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AiIndex {

	static final int REASON_LENGTH = 300;

	@Id
	@Column(name = "analysis_id")
	private UUID analysisId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private IndexStatus status;

	@Column(name = "chunk_count", nullable = false, columnDefinition = "integer not null default 0")
	private int chunkCount;

	@Column(name = "embedded_count", nullable = false, columnDefinition = "integer not null default 0")
	private int embeddedCount;

	@Column(name = "embedding_model", length = 100)
	private String embeddingModel;

	/** A user-safe message: why it FAILED or is EMPTY, or a note on a READY index that covers only part of the repository. */
	@Column(name = "failure_reason", length = REASON_LENGTH)
	private String failureReason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "indexed_at")
	private Instant indexedAt;

	static AiIndex create(UUID analysisId, IndexStatus status, int chunkCount, String reason, Instant now) {
		AiIndex index = new AiIndex();
		index.analysisId = analysisId;
		index.status = status;
		index.chunkCount = chunkCount;
		index.embeddedCount = 0;
		index.embeddingModel = EmbeddingSpec.MODEL_NAME;
		index.failureReason = reason == null ? null : truncate(reason);
		index.createdAt = now;
		index.updatedAt = now;
		return index;
	}

	static String truncate(String reason) {
		return reason.length() <= REASON_LENGTH ? reason : reason.substring(0, REASON_LENGTH);
	}
}
