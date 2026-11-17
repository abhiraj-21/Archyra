package com.abhiraj.archyra.analysis;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyses")
@RequiredArgsConstructor
public class AnalysisController {

	private final AnalysisService analysisService;

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public CreateAnalysisResponse createAnalysis(@Valid @RequestBody CreateAnalysisRequest request) {
		return analysisService.createAnalysis(request);
	}

	/** The caller's own analyses, newest first. */
	@GetMapping
	public AnalysisPageResponse listAnalyses(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + AnalysisService.DEFAULT_PAGE_SIZE) int size) {
		return analysisService.list(page, size);
	}

	@DeleteMapping("/{analysisId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAnalysis(@PathVariable String analysisId) {
		analysisService.delete(analysisId);
	}

	@GetMapping("/{analysisId}")
	public AnalysisStatusResponse getAnalysisStatus(@PathVariable String analysisId) {
		return analysisService.getStatus(analysisId);
	}
}
