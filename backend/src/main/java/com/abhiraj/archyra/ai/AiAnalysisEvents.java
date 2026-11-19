package com.abhiraj.archyra.ai;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.index.AiIndexService;
import com.abhiraj.archyra.ai.index.ChunkExtractor;
import com.abhiraj.archyra.ai.index.ChunkSet;
import com.abhiraj.archyra.common.AnalysisCompleted;
import com.abhiraj.archyra.common.AnalysisDeleting;
import com.abhiraj.archyra.common.AnalysisFailed;
import com.abhiraj.archyra.common.AnalysisGraphBuilt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Where Ask AI hears about analyses. The pipeline publishes the events, so {@code analysis/} never imports this
 * package.
 *
 * <ul>
 *   <li>{@link AnalysisGraphBuilt}: capture the chunk text while the clone still exists. Synchronous and <b>never
 *       fails the analysis</b>: any error becomes a FAILED index.</li>
 *   <li>{@link AnalysisCompleted}: hand the index to the embedding job.</li>
 *   <li>{@link AnalysisFailed}: drop what was captured.</li>
 *   <li>{@link AnalysisDeleting}: drop everything before the analysis row goes. If that throws, the delete stops and the
 *       row stays, so a retry still finds everything.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AiAnalysisEvents {

	private final ChunkExtractor extractor;
	private final AiIndexService index;
	private final AiDataPurger purger;

	@EventListener
	void onGraphBuilt(AnalysisGraphBuilt event) {
		try {
			ChunkSet captured = extractor.extract(event.graph(), event.root());
			index.storeCapture(event.analysisId(), captured);
			log.info("Captured {} chunks for analysis {}{}", captured.chunks().size(), event.analysisId(),
					captured.capped() ? " (capped)" : "");
		}
		catch (Throwable failure) { // nothing here may fail the analysis
			log.warn("Chunk capture failed for analysis {}; the analysis continues without Ask AI", event.analysisId(), failure);
			try {
				index.recordCaptureFailure(event.analysisId());
			}
			catch (Throwable recordFailure) {
				log.error("Could not record the chunk capture failure of analysis {}", event.analysisId(), recordFailure);
			}
		}
	}

	@EventListener
	void onCompleted(AnalysisCompleted event) {
		try {
			index.startEmbedding(event.analysisId());
		}
		catch (RuntimeException ex) {
			log.warn("Could not queue embedding for analysis {}; the sweeper will", event.analysisId(), ex);
		}
	}

	@EventListener
	void onFailed(AnalysisFailed event) {
		try {
			purger.purge(event.analysisId());
		}
		catch (RuntimeException ex) {
			log.warn("Could not drop the Ask AI data of failed analysis {}; startup recovery will", event.analysisId(), ex);
		}
	}

	@EventListener
	void onDeleting(AnalysisDeleting event) {
		purger.purge(event.analysisId()); // may throw: that stops the delete on purpose
	}
}
