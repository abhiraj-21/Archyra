package com.abhiraj.archyra.ai.llm;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.llm.ProviderException.Kind;
import com.abhiraj.archyra.ai.llm.ProviderSettings.Auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Any provider that speaks the OpenAI chat-completions protocol, driven only by its {@link ProviderSettings}: Groq,
 * OpenRouter, Cloudflare, Z AI, Cohere, OVHcloud, LLM7 and the opt-in ones. Plain {@code RestClient} on purpose: the safety
 * rules need exact control of the headers (a provider with {@code auth=none} gets no {@code Authorization} header at all),
 * of the errors (status and {@code Retry-After} only, a body never reaches a message) and of the answer (only
 * {@code message.content} is read; {@code reasoning_content} and friends are never looked at). One attempt, no retries:
 * the gateway owns the fallback.
 */
final class OpenAiCompatibleChatProvider implements ChatProvider {

	private static final double TEMPERATURE = 0.2;
	private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
	private static final Set<String> RESERVED = Set.of("model", "messages", "stream");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ProviderSettings settings;
	private final AiProperties properties;
	private volatile RestClient client;

	OpenAiCompatibleChatProvider(ProviderSettings settings, AiProperties properties) {
		this.settings = settings;
		this.properties = properties;
	}

	@Override
	public String id() {
		return settings.id();
	}

	@Override
	public String displayName() {
		return settings.displayName();
	}

	@Override
	public int contextBudgetTokens() {
		return settings.maxContextTokens();
	}

	@Override
	public void warmUp() {
		client();
	}

	@Override
	public LlmAnswer chat(LlmRequest request) {
		String body = body(request);
		try {
			return client().post().uri(settings.baseUrl().strip().replaceAll("/+$", "") + "/chat/completions")
					.headers(this::headers).body(body).exchange((req, res) -> {
						int status = res.getStatusCode().value();
						if (status < 200 || status >= 300) {
							Kind kind = ProviderErrors.kindOf(status);
							Duration retryAfter = kind == Kind.RATE_LIMITED ? ProviderErrors.retryAfter(res.getHeaders().getFirst("Retry-After")) : null;
							throw new ProviderException(settings.id(), kind, retryAfter, null);
						}
						return answer(res.getBody().readNBytes(MAX_RESPONSE_BYTES));
					});
		}
		catch (ProviderException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw ProviderErrors.classify(settings.id(), ex);
		}
	}

	private void headers(HttpHeaders headers) {
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(List.of(MediaType.APPLICATION_JSON));
		boolean send = settings.auth() == Auth.BEARER || (settings.auth() == Auth.OPTIONAL && settings.hasKey());
		if (send) {
			headers.setBearerAuth(settings.apiKey().strip());
		}
	}

	private String body(LlmRequest request) {
		List<Map<String, String>> messages = new ArrayList<>();
		messages.add(Map.of("role", "system", "content", request.system()));
		for (ChatTurn turn : request.history()) {
			messages.add(Map.of("role", turn.role() == ChatTurn.Role.USER ? "user" : "assistant", "content", turn.content()));
		}
		messages.add(Map.of("role", "user", "content", request.userMessage()));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", settings.model());
		body.put("messages", messages);
		body.put("temperature", TEMPERATURE);
		body.put("max_tokens", properties.answer().maxOutputTokens());
		body.put("stream", false);
		if (settings.reasoningEffort() != null && !settings.reasoningEffort().isBlank()) {
			body.put("reasoning_effort", settings.reasoningEffort().strip());
		}
		settings.extraBody().forEach((key, value) -> {
			if (!RESERVED.contains(key)) {
				body.put(key, value);
			}
		});
		return JSON.writeValueAsString(body);
	}

	/** The cleaned text of a 2xx body, or the failure it describes. */
	private LlmAnswer answer(byte[] bytes) throws IOException {
		JsonNode root;
		try {
			root = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
		}
		catch (RuntimeException ex) {
			throw new ProviderException(settings.id(), Kind.OTHER, null, null); // not JSON; nothing of it is kept
		}
		JsonNode choice = root.path("choices").path(0);
		if (choice.isMissingNode()) {
			// some providers (OpenRouter) report a failure inside a 200: classify it like the status it carries
			JsonNode error = root.path("error");
			if (!error.isMissingNode()) {
				int code = error.path("code").asInt(0);
				throw new ProviderException(settings.id(), code == 0 ? Kind.UNAVAILABLE : ProviderErrors.kindOf(code), null, null);
			}
			throw new ProviderException(settings.id(), Kind.EMPTY, null, null);
		}
		if ("content_filter".equals(choice.path("finish_reason").asString(""))) {
			throw new ProviderException(settings.id(), Kind.EMPTY, null, null);
		}
		String text = AnswerText.clean(content(choice.path("message").path("content")));
		if (text.isEmpty()) {
			throw new ProviderException(settings.id(), Kind.EMPTY, null, null);
		}
		return new LlmAnswer(text, settings.id());
	}

	/** {@code content} is a string, or a list of parts with a {@code text} (some providers). */
	private static String content(JsonNode content) {
		if (content.isString()) {
			return content.asString();
		}
		if (content.isArray()) {
			StringBuilder text = new StringBuilder();
			for (JsonNode part : content) {
				if (part.path("text").isString()) {
					text.append(part.path("text").asString());
				}
			}
			return text.toString();
		}
		return "";
	}

	private RestClient client() {
		RestClient built = client;
		if (built == null) {
			synchronized (this) {
				built = client;
				if (built == null) {
					built = build();
					client = built;
				}
			}
		}
		return built;
	}

	private RestClient build() {
		HttpClient http = HttpClient.newBuilder().connectTimeout(properties.timeout().connect()).version(HttpClient.Version.HTTP_1_1).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(properties.timeout().read());
		return RestClient.builder().requestFactory(factory).build();
	}
}
