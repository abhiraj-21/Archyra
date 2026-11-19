package com.abhiraj.archyra.ai.index;

import java.time.Instant;

/**
 * Body of {@code GET} and {@code POST /api/v1/analyses/{analysisId}/ai-index}.
 *
 * @param status    {@code NOT_INDEXED} (no index row: analysed before Ask AI existed), or the stored status
 * @param retryable true only for a FAILED index whose chunks exist, so embedding can be tried again for free
 * @param reason    a safe message for FAILED and EMPTY, or a note on a READY index that covers only part of the repository
 */
public record AiIndexResponse(
		State status,
		int chunkCount,
		int embeddedCount,
		String embeddingModel,
		boolean retryable,
		String reason,
		Instant indexedAt) {

	public enum State {
		NOT_INDEXED, INDEXING, READY, FAILED, EMPTY
	}

	static AiIndexResponse notIndexed() {
		return new AiIndexResponse(State.NOT_INDEXED, 0, 0, null, false, null, null);
	}
}
