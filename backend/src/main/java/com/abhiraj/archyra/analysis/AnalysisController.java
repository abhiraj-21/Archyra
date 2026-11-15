package com.abhiraj.archyra.analysis;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
