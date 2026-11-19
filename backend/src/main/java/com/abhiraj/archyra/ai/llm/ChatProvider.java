package com.abhiraj.archyra.ai.llm;

/**
 * One chat provider behind the gateway. Nothing else in the application talks to a provider: questions go through
 * {@link ProviderGateway}. Only active providers (enabled, configured) are ever instantiated.
 */
public interface ChatProvider {

	/** The configured id, for example {@code groq}. */
	String id();

	String displayName();

	/** The most context, in estimated tokens, that fits this provider's limits. */
	int contextBudgetTokens();

	/** Builds the client now (no network), so the first question does not pay for it. */
	void warmUp();

	/** One call, no retries. @throws ProviderException when there is no answer */
	LlmAnswer chat(LlmRequest request);
}
