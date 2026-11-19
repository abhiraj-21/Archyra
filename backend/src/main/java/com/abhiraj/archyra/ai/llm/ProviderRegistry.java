package com.abhiraj.archyra.ai.llm;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.llm.ProviderSettings.Auth;
import com.abhiraj.archyra.ai.llm.ProviderSettings.Protocol;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the provider blocks {@code archyra.ai.providers.<id>.*}, checks them and builds the chain once, at startup.
 * A provider that is disabled or has no key is simply not in the chain (never an error); a provider that breaks a safety
 * rule (see {@link ProviderRules}) stops the application from starting, with a message that names the setting.
 *
 * <p>Adding a provider that speaks the OpenAI protocol is one config block plus its id in
 * {@code archyra.ai.providers.order}; no code changes. Nothing here logs a key.
 */
@Slf4j
@Component
public class ProviderRegistry {

	/** One known provider: its settings, its local limits, and its client when it is in the chain. */
	record Slot(ProviderSettings settings, ProviderGuard guard, ChatProvider provider) {
		boolean active() {
			return provider != null;
		}
	}

	private static final String PREFIX = "archyra.ai.providers.";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final List<Slot> slots;

	ProviderRegistry(Environment environment, AiProperties properties, Clock clock) {
		Binder binder = Binder.get(environment);
		List<ProviderSettings> resolved = new ArrayList<>();
		for (String id : knownIds(properties)) {
			ProviderConfig config = binder.bind(PREFIX + id, ProviderConfig.class).orElse(null);
			if (config == null) {
				log.warn("Ask AI provider '{}' is listed but has no configuration block ({}{}.*); it is skipped", id, PREFIX, id);
				continue;
			}
			resolved.add(resolve(id, config, properties));
		}
		checkRules(resolved);
		List<Slot> built = new ArrayList<>();
		for (ProviderSettings settings : resolved) {
			ChatProvider provider = null;
			if (settings.active()) {
				provider = settings.protocol() == Protocol.GEMINI ? new GeminiChatProvider(settings, properties)
						: new OpenAiCompatibleChatProvider(settings, properties);
			}
			built.add(new Slot(settings, new ProviderGuard(settings, clock), provider));
		}
		this.slots = List.copyOf(built);
		report();
	}

	/** Every known provider, in chain order, active or not. */
	List<Slot> all() {
		return slots;
	}

	/** The providers that are in the chain, in the order they are tried. */
	List<Slot> chain() {
		return slots.stream().filter(Slot::active).toList();
	}

	/** The display name of a provider; the id itself when it is not (or no longer) configured. */
	public String displayName(String id) {
		if (id == null) {
			return null;
		}
		return slots.stream().filter(slot -> slot.settings().id().equals(id)).findFirst()
				.map(slot -> slot.settings().displayName()).orElse(id);
	}

	// ---- resolving -----------------------------------------------------------------------------

	/** The ids in {@code order}, then the opt-in providers that are not listed (appended, in a fixed order). */
	private static Set<String> knownIds(AiProperties properties) {
		Set<String> ids = new LinkedHashSet<>();
		for (String id : properties.providers().order()) {
			if (id != null && !id.isBlank()) {
				ids.add(id.strip().toLowerCase(Locale.ROOT));
			}
		}
		ids.addAll(ProviderRules.OPT_IN_ORDER);
		return ids;
	}

	private static ProviderSettings resolve(String id, ProviderConfig config, AiProperties properties) {
		boolean optIn = ProviderRules.OPT_IN.contains(id);
		boolean enabled = config.enabled() != null ? config.enabled() : !optIn;
		Protocol protocol = protocol(id, config.protocol());
		Auth auth = auth(id, config);
		int context = config.maxContextTokens() != null && config.maxContextTokens() > 0 ? config.maxContextTokens()
				: properties.providers().defaultMaxContextTokens();
		String name = config.displayName() == null || config.displayName().isBlank() ? id : config.displayName().strip();
		return new ProviderSettings(id, name, protocol, auth, enabled, optIn, strip(config.baseUrl()), config.apiKey(),
				strip(config.model()), context, atLeastZero(config.requestsPerMinute()), atLeastZero(config.maxConcurrent()),
				atLeastZero(config.maxRequestsPerDay()), atLeastZero(config.maxRequestsPerMonth()),
				Boolean.TRUE.equals(config.logsPrompts()), config.reasoningEffort(), extraBody(id, config.extraBody()));
	}

	private static Protocol protocol(String id, String value) {
		if (value == null || value.isBlank() || "openai".equalsIgnoreCase(value.strip())) {
			return Protocol.OPENAI;
		}
		if ("gemini".equalsIgnoreCase(value.strip())) {
			return Protocol.GEMINI;
		}
		throw new IllegalStateException("Ask AI provider '" + id + "' has an unknown protocol; use 'openai' or 'gemini'");
	}

	private static Auth auth(String id, ProviderConfig config) {
		if (ProviderRules.OVH.equals(id)) {
			if (config.apiKey() != null && !config.apiKey().isBlank()) {
				log.warn("A key is configured for '{}' and is ignored: that provider is used anonymously only, because keyed use is billed", id);
			}
			return Auth.NONE;
		}
		String value = config.auth();
		if (value == null || value.isBlank() || "bearer".equalsIgnoreCase(value.strip())) {
			return Auth.BEARER;
		}
		if ("optional".equalsIgnoreCase(value.strip())) {
			return Auth.OPTIONAL;
		}
		if ("none".equalsIgnoreCase(value.strip())) {
			return Auth.NONE;
		}
		throw new IllegalStateException("Ask AI provider '" + id + "' has an unknown auth mode; use 'bearer', 'optional' or 'none'");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> extraBody(String id, String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return JSON.readValue(json, Map.class);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Ask AI provider '" + id + "' has an extra-body that is not a JSON object");
		}
	}

	private static String strip(String value) {
		return value == null ? null : value.strip();
	}

	private static int atLeastZero(Integer value) {
		return value == null ? 0 : Math.max(0, value);
	}

	// ---- rules and reporting -------------------------------------------------------------------

	private static void checkRules(List<ProviderSettings> all) {
		for (ProviderSettings settings : all) {
			if (ProviderRules.OPENROUTER.equals(settings.id()) && settings.enabled()
					&& (settings.model() == null || !settings.model().endsWith(ProviderRules.OPENROUTER_FREE_SUFFIX))) {
				throw new IllegalStateException("OpenRouter is enabled but its model id does not end in '" + ProviderRules.OPENROUTER_FREE_SUFFIX
						+ "'. Refusing to start: a paid model would spend credits. Set OPENROUTER_MODEL to a free model.");
			}
		}
		Set<String> keys = new HashSet<>();
		for (ProviderSettings settings : all) {
			if (settings.active() && settings.auth() != Auth.NONE && settings.hasKey() && !keys.add(settings.apiKey().strip())) {
				throw new IllegalStateException("Two Ask AI providers are configured with the same API key (one of them is '" + settings.id()
						+ "'). Refusing to start: a key must only ever go to the provider it belongs to.");
			}
		}
	}

	private void report() {
		for (Slot slot : slots) {
			ProviderSettings settings = slot.settings();
			if (!settings.enabled()) {
				log.info("Ask AI provider {} is off{}", settings.id(), settings.optIn() ? " (opt-in: set enabled=true to use it)" : "");
				continue;
			}
			if (!settings.configured()) {
				log.info("Ask AI provider {} has no key (or no usable base URL); it is not in the chain", settings.id());
				continue;
			}
			log.info("Ask AI provider {} in the chain: model={} contextTokens={} perMinute={} concurrent={} perDay={} perMonth={}", settings.id(),
					settings.model(), settings.maxContextTokens(), settings.requestsPerMinute(), settings.maxConcurrent(),
					settings.maxRequestsPerDay(), settings.maxRequestsPerMonth());
			if (settings.optIn()) {
				log.warn("Ask AI provider {} is an opt-in provider and is enabled: check its data policy and terms (see the README)", settings.id());
			}
			if (settings.logsPrompts()) {
				log.warn("Ask AI provider {} may log prompts or use them for training: questions and code excerpts are sent to it", settings.id());
			}
			if (ProviderRules.COHERE.equals(settings.id())) {
				log.warn("Ask AI provider cohere is enabled: Cohere trial keys are for non-commercial use only");
			}
		}
		log.info("Ask AI provider chain: {}", chain().stream().map(slot -> slot.settings().id()).toList());
	}
}
