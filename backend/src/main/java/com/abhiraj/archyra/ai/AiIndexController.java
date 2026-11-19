package com.abhiraj.archyra.ai;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.ai.index.AiIndexResponse;
import com.abhiraj.archyra.ai.index.AiIndexService;
import com.abhiraj.archyra.analysis.AnalysisService;

import lombok.RequiredArgsConstructor;

/**
 * The Ask AI index of an analysis. Ownership and "has completed" are enforced by
 * {@link AnalysisService#requireCompleted}, like every read endpoint.
 */
@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/ai-index")
@RequiredArgsConstructor
class AiIndexController {

	private final AnalysisService analysisService;
	private final AiIndexService indexService;

	@GetMapping
	AiIndexResponse get(@PathVariable String analysisId) {
		UUID id = analysisService.requireCompleted(analysisId);
		return indexService.describe(id);
	}

	/** Retries a FAILED index: 202 when it starts, 200 unchanged for INDEXING, READY and EMPTY, 409 when only a re-analysis helps. */
	@PostMapping
	ResponseEntity<AiIndexResponse> retry(@PathVariable String analysisId) {
		UUID id = analysisService.requireCompleted(analysisId);
		AiIndexService.Retry outcome = indexService.retry(id);
		HttpStatus status = outcome == AiIndexService.Retry.STARTED ? HttpStatus.ACCEPTED : HttpStatus.OK;
		return ResponseEntity.status(status).body(indexService.describe(id));
	}
}
