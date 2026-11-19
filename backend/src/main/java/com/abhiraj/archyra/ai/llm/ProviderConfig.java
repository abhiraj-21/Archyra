package com.abhiraj.archyra.ai.llm;

/**
 * One block {@code archyra.ai.providers.<id>.*}, exactly as configured. Every field may be missing (null): the
 * {@link ProviderRegistry} resolves defaults and validates. The key is redacted in {@code toString}.
 *
 * @param protocol        {@code openai} (the default: OpenAI chat completions) or {@code gemini} (Google AI Studio)
 * @param enabled         false removes the provider from the chain; opt-in providers are off unless this is explicitly true
 * @param auth            {@code bearer} (the default: a key is required and sent), {@code optional} (sent only when a key is
 *                        set) or {@code none} (never sent)
 * @param model           the model id, configuration only
 * @param maxContextTokens the context budget of a prompt for this provider, in estimated tokens
 * @param requestsPerMinute local token bucket; 0 or missing means no local limit (same for the three below)
 * @param maxConcurrent   most calls in flight at once
 * @param logsPrompts     what the provider's terms say: free-tier prompts may be logged or used for training
 * @param reasoningEffort sent as {@code reasoning_effort} when not blank
 * @param extraBody       a JSON object merged into the request body (for example {@code {"thinking":{"type":"disabled"}}})
 */
public record ProviderConfig(String protocol, Boolean enabled, String displayName, String baseUrl, String apiKey, String auth,
		String model, Integer maxContextTokens, Integer requestsPerMinute, Integer maxConcurrent, Integer maxRequestsPerDay,
		Integer maxRequestsPerMonth, Boolean logsPrompts, String reasoningEffort, String extraBody) {

	@Override
	public String toString() {
		return "ProviderConfig[model=" + model + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "***") + "]";
	}
}
