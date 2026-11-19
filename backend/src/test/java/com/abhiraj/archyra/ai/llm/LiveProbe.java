package com.abhiraj.archyra.ai.llm;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.abhiraj.archyra.ai.AiProperties;

/**
 * For the opt-in live smoke tests: the real configuration of ONE provider (its real URL, model and key from the environment),
 * as a chain of one, asked through the real gateway. Empty when that provider has no key, so the test skips silently.
 */
public final class LiveProbe {

	private LiveProbe() {
	}

	/** The answer of provider {@code id} to a trivial question, or empty when it is not in the chain (no key, switched off). */
	public static Optional<LlmAnswer> ping(Environment real, AiProperties properties, String id) {
		// the real property sources, with only the order overridden: a chain of just this provider
		StandardEnvironment only = new StandardEnvironment();
		List.copyOf(only.getPropertySources().stream().map(source -> source.getName()).toList())
				.forEach(name -> only.getPropertySources().remove(name));
		only.getPropertySources().addFirst(new MapPropertySource("live-probe", Map.of("archyra.ai.providers.order", id)));
		((ConfigurableEnvironment) real).getPropertySources().forEach(source -> only.getPropertySources().addLast(source));
		AiProperties.Providers all = properties.providers();
		AiProperties single = new AiProperties(new AiProperties.Providers(List.of(id), all.maxAttempts(), all.defaultMaxContextTokens(),
				all.cooldownDefault(), all.cooldownMax(), all.cooldownMisconfigured(), all.minAttemptTimeout()), properties.timeout(),
				properties.answer(), properties.history(), properties.retrieval(), properties.chunk(), properties.embedding(), properties.cache(),
				properties.limits());
		Clock clock = Clock.systemUTC();
		ProviderRegistry registry = new ProviderRegistry(only, single, clock);
		if (registry.chain().isEmpty()) {
			return Optional.empty();
		}
		ProviderGateway gateway = new ProviderGateway(registry, new ProviderCooldowns(single, clock), single, clock);
		try {
			LlmRequest request = new LlmRequest("Answer in one short sentence.", List.of(), "Reply with the single word: pong");
			return Optional.of(gateway.ask(provider -> new ProviderGateway.Prepared<>(request, null)).answer());
		}
		finally {
			gateway.destroy();
		}
	}
}
