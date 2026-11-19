package com.abhiraj.archyra.ai.index;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.common.AsyncConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Embeds the chunks of an index in the background, on the dedicated {@code aiIndexExecutor}, after the analysis has
 * COMPLETED. A job embeds the chunks that have no vector yet in batches, committing each batch, so a restart or a retry
 * simply continues. It stops quietly when the analysis disappears. Whatever goes wrong ends as FAILED with a safe
 * reason (retryable, because the chunks exist); nothing here can touch the analysis itself.
 */
@Slf4j
@Component
class EmbeddingJobs {

	static final String EMBEDDING_FAILED = "The code could not be embedded. You can retry.";

	private final Executor executor;
	private final EmbeddingService embeddings;
	private final ChunkStore chunks;
	private final AiIndexRepository indexes;
	private final AnalysisService analyses;
	private final AiProperties properties;
	private final Clock clock;
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	EmbeddingJobs(@Qualifier(AsyncConfig.AI_INDEX_EXECUTOR) Executor executor, EmbeddingService embeddings,
			ChunkStore chunks, AiIndexRepository indexes, AnalysisService analyses, AiProperties properties,
			Clock clock) {
		this.executor = executor;
		this.embeddings = embeddings;
		this.chunks = chunks;
		this.indexes = indexes;
		this.analyses = analyses;
		this.properties = properties;
		this.clock = clock;
	}

	/** True while a job for this analysis is queued or running. */
	boolean isInFlight(UUID analysisId) {
		return inFlight.contains(analysisId);
	}

	/**
	 * Queues a job unless one is already queued or running. A full queue is not an error: the row stays INDEXING and
	 * the sweeper submits it again later.
	 *
	 * @return true if a job was queued
	 */
	boolean submit(UUID analysisId) {
		if (!inFlight.add(analysisId)) {
			return false;
		}
		try {
			executor.execute(() -> {
				try {
					run(analysisId);
				}
				finally {
					inFlight.remove(analysisId);
				}
			});
			return true;
		}
		catch (RejectedExecutionException ex) {
			inFlight.remove(analysisId);
			log.warn("The embedding queue is full; analysis {} stays INDEXING and is picked up later", analysisId);
			return false;
		}
	}

	/** The job body. Package-private so tests can run it on the calling thread. */
	void run(UUID analysisId) {
		try {
			int batchSize = properties.embedding().batchSize();
			while (true) {
				if (!analyses.isCompleted(analysisId)) {
					log.info("Analysis {} is gone or not completed; embedding stopped", analysisId);
					return;
				}
				AiIndex index = indexes.findById(analysisId).orElse(null);
				if (index == null || index.getStatus() != IndexStatus.INDEXING) {
					return; // deleted, or finished or failed by someone else
				}
				List<ChunkStore.Pending> batch = chunks.nextUnembedded(analysisId, batchSize);
				if (batch.isEmpty()) {
					break;
				}
				List<float[]> vectors = embeddings.embedAll(
						batch.stream().map(chunk -> ChunkStore.embeddingInput(chunk.nodeId(), chunk.content())).toList());
				chunks.storeEmbeddings(batch, vectors);
				indexes.setEmbeddedCount(analysisId, chunks.countEmbedded(analysisId), clock.instant());
			}
			AiIndex index = indexes.findById(analysisId).orElse(null);
			if (index != null) {
				indexes.setEmbeddedCount(analysisId, chunks.countEmbedded(analysisId), clock.instant());
				int marked = indexes.markReady(analysisId, clock.instant(), AiIndexService.partialNote(index.getChunkCount(), properties));
				if (marked > 0) {
					log.info("Index of analysis {} is READY ({} chunks)", analysisId, index.getChunkCount());
				}
			}
		}
		catch (EmbeddingClosedException shuttingDown) {
			log.info("Embedding of analysis {} stopped because the application is shutting down; it resumes at the next start", analysisId);
		}
		catch (Throwable failure) { // an index must never stay INDEXING because of a job that died
			log.error("Embedding failed for analysis {}", analysisId, failure);
			try {
				indexes.markFailed(analysisId, clock.instant(), EMBEDDING_FAILED);
			}
			catch (Throwable ignored) {
				log.error("Could not record the embedding failure of analysis {}; the sweeper will resume it", analysisId);
			}
		}
	}
}
