package com.abhiraj.archyra.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Everything about Ask AI that is configurable ({@code archyra.ai.*}). Model ids, base URLs, limits and budgets live
 * only here, in {@code application.properties}, the README and {@code .env.example}, never as Java literals. API keys
 * come from the environment and may be empty (a missing key must not fail startup: that provider is simply not
 * configured). Neither this record nor its parts ever print a key.
 *
 * @param gemini    the primary chat provider (Google AI Studio)
 * @param groq      the fallback chat provider (Groq, OpenAI-compatible)
 * @param timeout   HTTP timeouts of a provider call and the budget of one whole question
 * @param provider  cool-down after a provider answered 429
 * @param answer    limits of one answer
 * @param context   per-provider budget of the context sent with a question, in (estimated) tokens
 * @param history   how much of the conversation goes back to the model
 * @param retrieval hybrid retrieval settings (vector-seeds-expanded: how many of the best vector hits are expanded into graph facts)
 * @param chunk     code chunk limits
 * @param embedding the local embedding model and the indexing batches
 * @param cache     answer cache
 * @param limits    request and conversation limits
 */
@Validated
@ConfigurationProperties(prefix = "archyra.ai")
public record AiProperties(
		@Valid @DefaultValue Gemini gemini,
		@Valid @DefaultValue Groq groq,
		@Valid @DefaultValue Timeout timeout,
		@Valid @DefaultValue Provider provider,
		@Valid @DefaultValue Answer answer,
		@Valid @DefaultValue Context context,
		@Valid @DefaultValue History history,
		@Valid @DefaultValue Retrieval retrieval,
		@Valid @DefaultValue Chunk chunk,
		@Valid @DefaultValue Embedding embedding,
		@Valid @DefaultValue Cache cache,
		@Valid @DefaultValue Limits limits) {

	/** Connection details of one chat provider. The key is redacted in {@code toString}. */
	public interface ProviderSettings {

		String apiKey();

		String model();

		String baseUrl();

		/** A provider without a key is not configured and is never called. */
		default boolean configured() {
			return apiKey() != null && !apiKey().isBlank();
		}
	}

	public record Gemini(String apiKey, @NotBlank String model, @NotBlank String baseUrl) implements ProviderSettings {

		@Override
		public String toString() {
			return "Gemini[model=" + model + ", baseUrl=" + baseUrl + ", apiKey=" + (configured() ? "***" : "unset") + "]";
		}
	}

	/** @param reasoningEffort sent as {@code reasoning_effort} when not blank (reasoning models spend output tokens on thinking; leave blank for other models) */
	public record Groq(String apiKey, @NotBlank String model, @NotBlank String baseUrl, String reasoningEffort) implements ProviderSettings {

		@Override
		public String toString() {
			return "Groq[model=" + model + ", baseUrl=" + baseUrl + ", apiKey=" + (configured() ? "***" : "unset") + "]";
		}
	}

	public record Timeout(
			@DefaultValue("5s") @NotNull Duration connect,
			@DefaultValue("40s") @NotNull Duration read,
			@DefaultValue("60s") @NotNull Duration total) {
	}

	public record Provider(
			@DefaultValue("30s") @NotNull Duration cooldownDefault,
			@DefaultValue("60s") @NotNull Duration cooldownMax) {
	}

	public record Answer(@DefaultValue("1024") @Min(1) int maxOutputTokens) {
	}

	public record Context(
			@DefaultValue("12000") @Min(500) int geminiMaxTokens,
			@DefaultValue("4500") @Min(500) int groqMaxTokens) {
	}

	/**
	 * @param turns     how many of the latest question and answer pairs go back to the model
	 * @param maxTokens estimated token cap of that history (older turns are dropped first), so history cannot crowd out the
	 *                  context or push a request over a provider's per-request limit
	 */
	public record History(@DefaultValue("4") @Min(0) int turns, @DefaultValue("1500") @Min(0) int maxTokens) {
	}

	public record Retrieval(
			@DefaultValue("12") @Min(1) int vectorTopK,
			@DefaultValue("0.25") @DecimalMin("-1.0") @DecimalMax("1.0") double minSimilarity,
			@DefaultValue("5") @Min(0) int vectorSeedsExpanded,
			@DefaultValue("8") @Min(0) int nameLookupMaxNodes,
			@DefaultValue("6") @Min(0) int neighborsPerNode) {
	}

	public record Chunk(
			@DefaultValue("6000") @Min(200) int maxChars,
			@DefaultValue("20000") @Min(1) int maxPerAnalysis) {
	}

	/**
	 * @param modelUri     the ONNX model: {@code file:}, {@code classpath:} or {@code https:} (downloaded once into
	 *                     {@code cacheDir}, so the app works offline afterwards)
	 * @param tokenizerUri the matching {@code tokenizer.json}
	 * @param cacheDir     where downloaded model files are kept (git-ignored)
	 * @param verifyOnStartup load the model at startup and fail fast if its output size is not the column's size
	 */
	public record Embedding(
			@DefaultValue("32") @Min(1) int batchSize,
			@NotBlank String modelUri,
			@NotBlank String tokenizerUri,
			@DefaultValue("models") @NotBlank String cacheDir,
			@DefaultValue("true") boolean verifyOnStartup) {
	}

	public record Cache(
			@DefaultValue("1h") @NotNull Duration ttl,
			@DefaultValue("500") @Min(1) int maxEntries) {
	}

	public record Limits(
			@DefaultValue("2000") @Min(1) int messageMaxChars,
			@DefaultValue("50") @Min(1) int conversationsPerAnalysis,
			@DefaultValue("100") @Min(2) int messagesPerConversation) {
	}
}
