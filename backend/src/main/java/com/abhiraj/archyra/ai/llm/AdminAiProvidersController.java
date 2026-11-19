package com.abhiraj.archyra.ai.llm;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** {@code GET /api/v1/admin/ai/providers}: the state of the provider chain, ADMIN only (the route rule, re-checked in the database). */
@RestController
@RequestMapping("/api/v1/admin/ai/providers")
@RequiredArgsConstructor
class AdminAiProvidersController {

	private final AdminAiProviders providers;

	@GetMapping
	List<ProviderStatus> list() {
		return providers.list();
	}
}
