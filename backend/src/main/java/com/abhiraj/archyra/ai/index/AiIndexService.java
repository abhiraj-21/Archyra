package com.abhiraj.archyra.ai.index;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.common.AiRuleException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The index lifecycle of one analysis: created when the chunks are captured, embedded in the background, READY when
 * every chunk has a vector. The entry point for the controller and for the other parts of Ask AI; the repository and
 * the chunk store stay inside this package.
 *
 * <p>Nothing here ever touches the analysis itself: an index can be absent, FAILED or EMPTY while the analysis is
 * COMPLETED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiIndexService {

	static final String EMPTY_REASON = "This repository has no non-test code to ask about.";
	static final String CAPTURE_FAILED_REASON = "The code could not be prepared for Ask AI. Re-analyze the repository to try again.";
	static final String PARTIAL_NOTE = "This is a large repository: only the first part of its code is indexed, so answers may not cover all of it.";

	private final AiIndexRepository indexes;
	private final ChunkStore chunks;
	private final EmbeddingJobs jobs;
	private final AiProperties properties;
	private final Clock clock;

	/**
	 * Stores the captured chunks and the index row, in one transaction: INDEXING with the chunk count, or EMPTY when
	 * there is nothing to ask about. Replaces whatever an earlier attempt left.
	 */
	@Transactional
	public void storeCapture(UUID analysisId, ChunkSet captured) {
		chunks.deleteByAnalysis(analysisId);
		indexes.deleteByAnalysisIdIn(java.util.List.of(analysisId));
		if (captured.chunks().isEmpty()) {
			indexes.saveAndFlush(AiIndex.create(analysisId, IndexStatus.EMPTY, 0, EMPTY_REASON, clock.instant()));
			return;
		}
		chunks.insertAll(analysisId, captured.chunks());
		String note = captured.capped() ? PARTIAL_NOTE : null;
		indexes.saveAndFlush(AiIndex.create(analysisId, IndexStatus.INDEXING, captured.chunks().size(), note, clock.instant()));
	}

	/** Chunk capture failed: no chunks, FAILED, not retryable (it needs a new analysis). */
	@Transactional
	public void recordCaptureFailure(UUID analysisId) {
		chunks.deleteByAnalysis(analysisId);
		indexes.deleteByAnalysisIdIn(java.util.List.of(analysisId));
		indexes.saveAndFlush(AiIndex.create(analysisId, IndexStatus.FAILED, 0, CAPTURE_FAILED_REASON, clock.instant()));
	}

	/** Removes the index row and every chunk of the analysis. Idempotent. */
	@Transactional
	public void purge(UUID analysisId) {
		chunks.deleteByAnalysis(analysisId);
		indexes.deleteByAnalysisIdIn(java.util.List.of(analysisId));
	}

	/** Hands a finished analysis to the embedding job (a no-op without an INDEXING row). */
	public void startEmbedding(UUID analysisId) {
		if (indexes.findById(analysisId).filter(index -> index.getStatus() == IndexStatus.INDEXING).isPresent()) {
			jobs.submit(analysisId);
		}
	}

	/** The state the API shows. An analysis without a row is {@code NOT_INDEXED}. */
	public AiIndexResponse describe(UUID analysisId) {
		return indexes.findById(analysisId).map(this::describe).orElseGet(AiIndexResponse::notIndexed);
	}

	/** Throws {@code AI_NOT_READY} unless the index is READY. */
	public void requireReady(UUID analysisId) {
		boolean ready = indexes.findById(analysisId).map(index -> index.getStatus() == IndexStatus.READY).orElse(false);
		if (!ready) {
			throw AiRuleException.notReady();
		}
	}

	/** Result of {@link #retry}. */
	public enum Retry {
		STARTED, UNCHANGED
	}

	/**
	 * Tries a FAILED index again, for free (no quota is involved). Only the caller that flips FAILED to INDEXING starts
	 * the job; INDEXING, READY and EMPTY are left alone.
	 *
	 * @throws AiRuleException {@code AI_REANALYZE_REQUIRED} for an analysis without an index, or a FAILED one that has no chunks
	 */
	public Retry retry(UUID analysisId) {
		Optional<AiIndex> index = indexes.findById(analysisId);
		if (index.isEmpty()) {
			throw AiRuleException.reanalyzeRequired();
		}
		return switch (index.get().getStatus()) {
			case INDEXING, READY, EMPTY -> Retry.UNCHANGED;
			case FAILED -> {
				if (index.get().getChunkCount() == 0 || chunks.count(analysisId) == 0) {
					throw AiRuleException.reanalyzeRequired();
				}
				if (indexes.resumeFromFailed(analysisId, clock.instant(),
						partialNote(index.get().getChunkCount(), properties)) == 0) {
					yield Retry.UNCHANGED; // someone else just retried it
				}
				jobs.submit(analysisId);
				yield Retry.STARTED;
			}
		};
	}

	private AiIndexResponse describe(AiIndex index) {
		boolean retryable = index.getStatus() == IndexStatus.FAILED && index.getChunkCount() > 0;
		return new AiIndexResponse(AiIndexResponse.State.valueOf(index.getStatus().name()), index.getChunkCount(),
				index.getEmbeddedCount(), index.getEmbeddingModel(), retryable, index.getFailureReason(), index.getIndexedAt());
	}

	/** The note of a READY or INDEXING index that hit the per-analysis chunk cap (the cap is also the count then). */
	static String partialNote(int chunkCount, AiProperties properties) {
		return chunkCount >= properties.chunk().maxPerAnalysis() ? PARTIAL_NOTE : null;
	}
}
