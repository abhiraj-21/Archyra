package com.abhiraj.archyra.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * WireMock as the fake Gemini and Groq HTTP servers. The fake model computes its answer from the request it receives (the
 * prompt), so a test can cite the number a context item really got. Both speak the real wire formats: Gemini's
 * {@code generateContent} and the OpenAI-compatible {@code chat/completions} that Groq serves.
 */
public final class FakeProviders {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Pattern ITEM = Pattern.compile("\\[(\\d+)\\] (?:CLASS|INTERFACE|METHOD|FUNCTION) (\\S+) ");

	public enum Shape {
		GEMINI, OPENAI
	}

	/** One fake provider. */
	public static final class Server {

		private final Shape shape;
		private final WireMockServer wireMock;
		private volatile Function<String, String> answer = prompt -> "default answer";

		Server(Shape shape) {
			this.shape = shape;
			this.wireMock = new WireMockServer(wireMockConfig().dynamicPort().extensions(new Transformer(this)));
			wireMock.start();
			reset();
		}

		public String baseUrl() {
			return wireMock.baseUrl();
		}

		/** Back to "answers with the default text": no failure stubs, a cleared journal. */
		public void reset() {
			wireMock.resetAll();
			answer = prompt -> "default answer";
			wireMock.stubFor(WireMock.post(urlPathMatching(".*")).willReturn(aResponse().withTransformers("fake-model")));
		}

		/** The answer is computed from the raw request body (the prompt, JSON-escaped). */
		public void answerWith(Function<String, String> answer) {
			this.answer = answer;
		}

		public void answerWith(String text) {
			this.answer = prompt -> text;
		}

		/** Every request now gets this status, with a body that must never reach a client. */
		public void failWith(int status) {
			wireMock.stubFor(WireMock.post(urlPathMatching(".*")).atPriority(1)
					.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
							.withBody("{\"error\":{\"message\":\"PROVIDER-ERROR-BODY\",\"code\":" + status + "}}")));
		}

		/** A 429 with a {@code Retry-After}. */
		public void rateLimitedFor(int seconds) {
			wireMock.stubFor(WireMock.post(urlPathMatching(".*")).atPriority(1)
					.willReturn(aResponse().withStatus(429).withHeader("Retry-After", String.valueOf(seconds))
							.withHeader("Content-Type", "application/json").withBody("{\"error\":{\"message\":\"PROVIDER-ERROR-BODY\",\"code\":429}}")));
		}

		/** Every request waits this long before it is answered. */
		public void delayBy(int millis) {
			wireMock.stubFor(WireMock.post(urlPathMatching(".*")).atPriority(1)
					.willReturn(aResponse().withFixedDelay(millis).withTransformers("fake-model")));
		}

		public int requestCount() {
			return wireMock.findAll(postRequestedFor(urlPathMatching(".*"))).size();
		}

		public List<LoggedRequest> requests() {
			return wireMock.findAll(postRequestedFor(urlPathMatching(".*")));
		}

		public LoggedRequest lastRequest() {
			List<LoggedRequest> all = requests();
			return all.getLast();
		}

		String answerFor(String requestBody) {
			return answer.apply(requestBody);
		}

		String json(String text) {
			if (shape == Shape.GEMINI) {
				return JSON.writeValueAsString(java.util.Map.of(
						"candidates", List.of(java.util.Map.of("index", 0, "finishReason", "STOP",
								"content", java.util.Map.of("role", "model", "parts", List.of(java.util.Map.of("text", text))))),
						"usageMetadata", java.util.Map.of("promptTokenCount", 10, "candidatesTokenCount", 5, "totalTokenCount", 15),
						"modelVersion", "fake"));
			}
			return JSON.writeValueAsString(java.util.Map.of(
					"id", "chatcmpl-fake", "object", "chat.completion", "created", 1700000000L, "model", "fake",
					"choices", List.of(java.util.Map.of("index", 0, "finish_reason", "stop",
							"message", java.util.Map.of("role", "assistant", "content", text))),
					"usage", java.util.Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
		}
	}

	private static final class Transformer implements ResponseDefinitionTransformerV2 {

		private final Server server;

		Transformer(Server server) {
			this.server = server;
		}

		@Override
		public ResponseDefinition transform(ServeEvent event) {
			String text = server.answerFor(event.getRequest().getBodyAsString());
			Integer delay = event.getResponseDefinition().getFixedDelayMilliseconds(); // a transformed response keeps its delay
			return new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder().withStatus(200).withFixedDelay(delay)
					.withHeader("Content-Type", "application/json").withBody(server.json(text)).build();
		}

		@Override
		public boolean applyGlobally() {
			return false;
		}

		@Override
		public String getName() {
			return "fake-model";
		}
	}

	public static final Server GEMINI = new Server(Shape.GEMINI);
	public static final Server GROQ = new Server(Shape.OPENAI);

	private FakeProviders() {
	}

	/** The number a context item got in a prompt: the {@code n} of {@code [n] METHOD <qualified name that contains the fragment>}. */
	public static int itemNumber(String requestBody, String qualifiedNameFragment) {
		Matcher matcher = ITEM.matcher(unescape(requestBody));
		while (matcher.find()) {
			if (matcher.group(2).contains(qualifiedNameFragment)) {
				return Integer.parseInt(matcher.group(1));
			}
		}
		throw new AssertionError("No context item for " + qualifiedNameFragment + " in the prompt");
	}

	/** How many numbered context items the prompt holds. */
	public static int itemCount(String requestBody) {
		Matcher matcher = Pattern.compile("(?m)^\\[(\\d+)\\] ").matcher(unescape(requestBody));
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	/** The request body as the model reads it: JSON string escapes undone. */
	public static String unescape(String body) {
		return body.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
	}
}
