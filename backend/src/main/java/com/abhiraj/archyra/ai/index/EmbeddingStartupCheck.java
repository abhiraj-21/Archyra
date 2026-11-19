package com.abhiraj.archyra.ai.index;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Fails startup if the embedding model is missing or does not produce the column's vector size. */
@Component
@ConditionalOnProperty(name = "archyra.ai.embedding.verify-on-startup", havingValue = "true")
@RequiredArgsConstructor
class EmbeddingStartupCheck implements ApplicationRunner {

	private final EmbeddingService embeddings;

	@Override
	public void run(ApplicationArguments args) {
		embeddings.verify();
	}
}
