package com.abhiraj.archyra.codebase;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/repository")
@RequiredArgsConstructor
public class RepositoryController {

	private final RepositoryService repositoryService;

	@GetMapping
	public RepositoryResponse getRepository(@PathVariable String analysisId) {
		return repositoryService.getRepository(analysisId);
	}
}
