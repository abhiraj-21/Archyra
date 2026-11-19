package com.abhiraj.archyra.ai;

import java.time.Duration;
import java.util.List;

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
 * @param providers the chain of chat providers: order, attempts, cool-downs. The providers themselves are the blocks
 *                  {@code archyra.ai.providers.<id>.*}, read by {@code ProviderRegistry} (see {@code ProviderConfig})
 * @param timeout   HTTP timeouts of a provider call and the budget of one whole question
 * @param answer    limits of one answer
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
		@Valid @DefaultValue Providers providers,
		@Valid @DefaultValue Timeout timeout,
		@Valid @DefaultValue Answer answer,
		@Valid @DefaultValue History history,
		@Valid @DefaultValue Retrieval retrieval,
		@Valid @DefaultValue Chunk chunk,
		@Valid @DefaultValue Embedding embedding,
		@Valid @DefaultValue Cache cache,
		@Valid @DefaultValue Limits limits) {

	/**
	 * The chain. {@code order} is the order providers are tried in; a provider without a key (or one that is disabled) is
	 * simply not in the chain. A provider that is {@code enabled} but not listed (the opt-in ones) is appended.
	 *
	 * @param order                  provider ids, in the order they are tried
	 * @param maxAttempts            the most providers one question calls (a local refusal is not an attempt)
	 * @param defaultMaxContextTokens context budget of a provider that sets none
	 * @param cooldownDefault        cool-down after a 429 without {@code Retry-After}; repeated 429s double it
	 * @param cooldownMax            cap of every 429 cool-down
	 * @param cooldownMisconfigured  cool-down after a bad key or an unknown model (401, 402, 403, 404)
	 * @param minAttemptTimeout      the least time one attempt gets, however many providers remain
	 */
	public record Providers(
			@DefaultValue List<String> order,
			@DefaultValue("4") @Min(1) int maxAttempts,
			@DefaultValue("4000") @Min(500) int defaultMaxContextTokens,
			@DefaultValue("30s") @NotNull Duration cooldownDefault,
			@DefaultValue("1h") @NotNull Duration cooldownMax,
			@DefaultValue("30m") @NotNull Duration cooldownMisconfigured,
			@DefaultValue("10s") @NotNull Duration minAttemptTimeout) {
	}

	public record Timeout(
			@DefaultValue("5s") @NotNull Duration connect,
			@DefaultValue("40s") @NotNull Duration read,
			@DefaultValue("60s") @NotNull Duration total) {
	}

	public record Answer(@DefaultValue("1024") @Min(1) int maxOutputTokens) {
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
