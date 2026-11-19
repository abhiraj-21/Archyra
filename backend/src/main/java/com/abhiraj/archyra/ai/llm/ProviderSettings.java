package com.abhiraj.archyra.ai.llm;

import java.net.URI;
import java.util.Map;

/**
 * One provider, resolved and checked. {@code active()} is what puts it in the chain. The key is redacted in
 * {@code toString}, and nothing here is ever logged except the id, the display name, the model and the limits.
 */
public record ProviderSettings(String id, String displayName, Protocol protocol, Auth auth, boolean enabled, boolean optIn,
		String baseUrl, String apiKey, String model, int maxContextTokens, int requestsPerMinute, int maxConcurrent,
		int maxRequestsPerDay, int maxRequestsPerMonth, boolean logsPrompts, String reasoningEffort, Map<String, Object> extraBody) {

	public enum Protocol {
		OPENAI, GEMINI
	}

	public enum Auth {
		/** A key is required and sent as a bearer token. */
		BEARER,
		/** A bearer token is sent when a key is set, nothing otherwise. */
		OPTIONAL,
		/** No {@code Authorization} header, ever. */
		NONE
	}

	public ProviderSettings {
		extraBody = Map.copyOf(extraBody);
	}

	public boolean hasKey() {
		return apiKey != null && !apiKey.isBlank();
	}

	/** Has what it needs to be called: a usable base URL, a model, and a key where the auth mode needs one. */
	public boolean configured() {
		return usableUrl(baseUrl) && model != null && !model.isBlank() && (auth != Auth.BEARER || hasKey());
	}

	/** In the chain: enabled and configured. */
	public boolean active() {
		return enabled && configured();
	}

	/** An http(s) URL with a host and no empty path segment (an unset account id leaves {@code accounts//ai}). */
	static boolean usableUrl(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(url.trim());
			boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
			return http && uri.getHost() != null && (uri.getPath() == null || !uri.getPath().contains("//"));
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	@Override
	public String toString() {
		return "ProviderSettings[id=" + id + ", model=" + model + ", baseUrl=" + baseUrl + ", apiKey=" + (hasKey() ? "***" : "unset") + "]";
	}
}
