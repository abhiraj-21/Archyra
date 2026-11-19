package com.abhiraj.archyra.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.FakeProviders;
import com.abhiraj.archyra.ai.llm.ProviderException.Kind;

/**
 * The two real providers (Spring AI with the Google GenAI and OpenAI SDKs) against the fake HTTP servers: what goes over the
 * wire, and how every failure is classified. No Spring context.
 */
class ChatProvidersWireTest {

	private static final LlmRequest REQUEST = new LlmRequest("SYSTEM RULES", List.of(new ChatTurn(ChatTurn.Role.USER, "earlier question"),
			new ChatTurn(ChatTurn.Role.ASSISTANT, "earlier answer")), "the final user message");

	private static AiProperties properties() {
		return new AiProperties(
				new AiProperties.Gemini("gem-key", "gem-model", FakeProviders.GEMINI.baseUrl()),
				new AiProperties.Groq("groq-key", "groq-model", FakeProviders.GROQ.baseUrl(), "low"),
				new AiProperties.Timeout(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(10)),
				new AiProperties.Provider(Duration.ofSeconds(30), Duration.ofSeconds(60)),
				new AiProperties.Answer(777), new AiProperties.Context(12000, 4500), new AiProperties.History(4, 1500), null, null, null, null, null);
	}

	private final GeminiChatProvider gemini = new GeminiChatProvider(properties());
	private final GroqChatProvider groq = new GroqChatProvider(properties());

	@BeforeEach
	void reset() {
		gemini.warmUp(); // as the gateway does at startup, so a first call does not pay for building its client
		groq.warmUp();
		FakeProviders.GEMINI.reset();
		FakeProviders.GROQ.reset();
	}

	@Test
	void geminiAnswersAndSendsTheKeyTheModelTheHistoryAndTheLimits() {
		FakeProviders.GEMINI.answerWith("hello from gemini");

		LlmAnswer answer = gemini.chat(REQUEST);

		assertThat(answer).isEqualTo(new LlmAnswer("hello from gemini", ProviderId.GEMINI));
		var request = FakeProviders.GEMINI.lastRequest();
		assertThat(request.getUrl()).contains("gem-model").contains("generateContent");
		assertThat(request.getHeader("x-goog-api-key")).isEqualTo("gem-key");
		String body = request.getBodyAsString();
		assertThat(body).contains("SYSTEM RULES").contains("earlier question").contains("earlier answer").contains("the final user message")
				.contains("\"maxOutputTokens\":777").contains("0.2");
		assertThat(body).doesNotContain("gem-key");
		assertThat(body.indexOf("earlier question")).isLessThan(body.indexOf("the final user message"));
	}

	@Test
	void groqAnswersAtTheOpenAiCompatiblePathWithTheKeyAsABearerToken() {
		FakeProviders.GROQ.answerWith("hello from groq");

		LlmAnswer answer = groq.chat(REQUEST);

		assertThat(answer).isEqualTo(new LlmAnswer("hello from groq", ProviderId.GROQ));
		var request = FakeProviders.GROQ.lastRequest();
		assertThat(request.getUrl()).isEqualTo("/chat/completions"); // the SDK appends this to the base URL, which therefore ends in /v1
		assertThat(request.getHeader("Authorization")).isEqualTo("Bearer groq-key");
		String body = request.getBodyAsString();
		assertThat(body).contains("groq-model").contains("SYSTEM RULES").contains("earlier question").contains("earlier answer")
				.contains("the final user message").contains("777").contains("\"reasoning_effort\":\"low\"");
		assertThat(body).doesNotContain("groq-key");
	}

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({ "GEMINI,429,RATE_LIMITED", "GEMINI,500,UNAVAILABLE", "GEMINI,503,UNAVAILABLE", "GEMINI,401,BAD_KEY", "GEMINI,403,BAD_KEY",
			"GEMINI,400,OTHER", "GROQ,429,RATE_LIMITED", "GROQ,500,UNAVAILABLE", "GROQ,503,UNAVAILABLE", "GROQ,401,BAD_KEY", "GROQ,403,BAD_KEY",
			"GROQ,400,OTHER" })
	void everyFailureIsClassifiedAndNoProviderBodyEscapes(ProviderId provider, int status, Kind expected) {
		FakeProviders.Server server = provider == ProviderId.GEMINI ? FakeProviders.GEMINI : FakeProviders.GROQ;
		ChatProvider chat = provider == ProviderId.GEMINI ? gemini : groq;
		server.failWith(status);

		assertThatThrownBy(() -> chat.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> {
			assertThat(ex.kind()).isEqualTo(expected);
			assertThat(ex.provider()).isEqualTo(provider);
			assertThat(ex.getMessage()).doesNotContain("PROVIDER-ERROR-BODY").doesNotContain("key");
		});
		assertThat(server.requestCount()).isEqualTo(1); // never retried, not even inside the SDK
	}

	@Test
	void groqsRetryAfterIsReadFromTheHeader() {
		FakeProviders.GROQ.rateLimitedFor(7);

		assertThatThrownBy(() -> groq.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class,
				ex -> assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(7)));
	}

	@Test
	void anEmptyAnswerIsAFailureForBoth() {
		FakeProviders.GEMINI.answerWith("");
		FakeProviders.GROQ.answerWith("");

		assertThatThrownBy(() -> gemini.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.kind()).isEqualTo(Kind.EMPTY));
		assertThatThrownBy(() -> groq.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.kind()).isEqualTo(Kind.EMPTY));
	}

	@Test
	void aDeadServerIsUnavailable() {
		AiProperties dead = new AiProperties(
				new AiProperties.Gemini("k", "m", "http://localhost:1"), new AiProperties.Groq("k", "m", "http://localhost:1", null),
				new AiProperties.Timeout(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(10)),
				null, new AiProperties.Answer(100), new AiProperties.Context(12000, 4500), null, null, null, null, null, null);

		assertThatThrownBy(() -> new GeminiChatProvider(dead).chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class,
				ex -> assertThat(ex.kind()).isIn(Kind.UNAVAILABLE, Kind.TIMEOUT));
		assertThatThrownBy(() -> new GroqChatProvider(dead).chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class,
				ex -> assertThat(ex.kind()).isIn(Kind.UNAVAILABLE, Kind.TIMEOUT));
	}

	@Test
	void aProviderWithoutAKeyIsNotConfigured() {
		AiProperties none = new AiProperties(new AiProperties.Gemini("", "m", "http://x"), new AiProperties.Groq(null, "m", "http://x", null),
				null, null, null, new AiProperties.Context(12000, 4500), null, null, null, null, null, null);

		assertThat(new GeminiChatProvider(none).configured()).isFalse();
		assertThat(new GroqChatProvider(none).configured()).isFalse();
		assertThat(gemini.contextBudgetTokens()).isEqualTo(12000);
		assertThat(groq.contextBudgetTokens()).isEqualTo(4500);
	}
}
