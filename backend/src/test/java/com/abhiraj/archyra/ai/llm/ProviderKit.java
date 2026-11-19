package com.abhiraj.archyra.ai.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.FakeProviders;

/**
 * The real provider chain (registry, cool-downs, gateway) built from properties without a Spring context, against the
 * fake servers. Nothing here is a mock: the properties are bound the way the application binds them.
 */
public final class ProviderKit {

	public static final String CHAT_OK = "default answer";

	private final Map<String, String> properties = new LinkedHashMap<>();
	private final MovableClock clock = new MovableClock();

	private ProviderKit() {
		properties.put("archyra.ai.timeout.connect", "1s");
		properties.put("archyra.ai.timeout.read", "3s");
		properties.put("archyra.ai.timeout.total", "20s");
		properties.put("archyra.ai.answer.max-output-tokens", "777");
		properties.put("archyra.ai.providers.order", "");
		properties.put("archyra.ai.providers.cooldown-default", "30s");
		properties.put("archyra.ai.providers.cooldown-max", "1h");
		properties.put("archyra.ai.providers.cooldown-misconfigured", "30m");
		properties.put("archyra.ai.providers.min-attempt-timeout", "1s");
	}

	public static ProviderKit create() {
		return new ProviderKit();
	}

	public ProviderKit set(String key, String value) {
		properties.put(key, value);
		return this;
	}

	/** The providers of the chain, in this order. */
	public ProviderKit order(String... ids) {
		properties.put("archyra.ai.providers.order", String.join(",", ids));
		return this;
	}

	public ProviderKit setting(String id, String key, String value) {
		properties.put("archyra.ai.providers." + id + "." + key, value);
		return this;
	}

	/** An OpenAI-compatible provider in front of a fake server, with the key {@code <id>-key} and the model {@code <id>-model}. */
	public ProviderKit openAi(String id, FakeProviders.Server server) {
		return openAi(id, server, id + "-key");
	}

	public ProviderKit openAi(String id, FakeProviders.Server server, String key) {
		setting(id, "display-name", id.toUpperCase());
		setting(id, "base-url", server.baseUrl());
		setting(id, "model", id + "-model");
		if (key != null) {
			setting(id, "api-key", key);
		}
		return this;
	}

	public ProviderKit gemini(FakeProviders.Server server) {
		setting("gemini", "protocol", "gemini");
		return openAi("gemini", server);
	}

	public Chain build() {
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
		AiProperties ai = new Binder(source).bind("archyra.ai", AiProperties.class).get();
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", new LinkedHashMap<>(properties)));
		ProviderRegistry registry = new ProviderRegistry(environment, ai, clock);
		ProviderCooldowns cooldowns = new ProviderCooldowns(ai, clock);
		ProviderGateway gateway = new ProviderGateway(registry, cooldowns, ai, clock);
		return new Chain(ai, registry, cooldowns, gateway, clock);
	}

	/** What a test works with. Close it to stop the gateway's executor. */
	public record Chain(AiProperties properties, ProviderRegistry registry, ProviderCooldowns cooldowns, ProviderGateway gateway,
			MovableClock clock) implements AutoCloseable {

		public static final LlmRequest REQUEST = new LlmRequest("SYSTEM RULES", java.util.List.of(), "the question");

		/** One question through the gateway; the answer's text, or an {@link AllProvidersFailedException}. */
		public LlmAnswer ask() {
			return gateway.ask(provider -> new ProviderGateway.Prepared<>(REQUEST, null)).answer();
		}

		@Override
		public void close() {
			gateway.destroy();
		}
	}
}
