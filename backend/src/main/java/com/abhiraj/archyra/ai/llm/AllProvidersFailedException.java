package com.abhiraj.archyra.ai.llm;

/** No provider was usable or every one that was tried failed. The chat flow turns it into {@code 503 AI_UNAVAILABLE}. */
public class AllProvidersFailedException extends RuntimeException {

	public AllProvidersFailedException(Throwable lastFailure) {
		super("No Ask AI provider could answer", lastFailure);
	}
}
