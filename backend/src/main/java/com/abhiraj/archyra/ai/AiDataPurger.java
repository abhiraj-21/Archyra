package com.abhiraj.archyra.ai;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.index.AiIndexService;

import lombok.RequiredArgsConstructor;

/** Removes everything Ask AI stores for an analysis. Idempotent. */
@Component
@RequiredArgsConstructor
class AiDataPurger {

	private final AiIndexService index;

	void purge(UUID analysisId) {
		index.purge(analysisId);
	}
}
