package com.abhiraj.archyra.ai.llm;

/**
 * One chat provider behind the gateway. Nothing else in the application talks to a provider: questions go through
 * {@link ProviderGateway}.
 */
public interface ChatProvider {

	ProviderId id();

	/** False without an API key; such a provider is never called. */
	boolean configured();

	/** The most context, in estimated tokens, that fits this provider's limits. */
	int contextBudgetTokens();

	/** Builds the SDK client now (no network), so the first question does not pay for it. A no-op without a key. */
	void warmUp();

	/** One call, no retries. @throws ProviderException when there is no answer */
	LlmAnswer chat(LlmRequest request);
}
