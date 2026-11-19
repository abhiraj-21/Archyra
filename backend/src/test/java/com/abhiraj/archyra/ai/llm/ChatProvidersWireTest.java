package com.abhiraj.archyra.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.abhiraj.archyra.ai.FakeProviders;
import com.abhiraj.archyra.ai.llm.ProviderException.Kind;

/**
 * The two kinds of provider client (Gemini through Spring AI's Google GenAI model, everything else through the generic
 * OpenAI-compatible client) against the fake HTTP servers: what goes over the wire, and how every failure is classified. No
 * Spring context.
 */
class ChatProvidersWireTest {

	private static final LlmRequest REQUEST = new LlmRequest("SYSTEM RULES", List.of(new ChatTurn(ChatTurn.Role.USER, "earlier question"),
			new ChatTurn(ChatTurn.Role.ASSISTANT, "earlier answer")), "the final user message");

	private ProviderKit.Chain chain;
	private ChatProvider gemini;
	private ChatProvider groq;

	@BeforeEach
	void build() {
		FakeProviders.resetAll();
		chain = ProviderKit.create().order("gemini", "groq").gemini(FakeProviders.GEMINI).openAi("groq", FakeProviders.GROQ)
				.setting("groq", "reasoning-effort", "low").setting("gemini", "max-context-tokens", "12000")
				.setting("groq", "max-context-tokens", "4500").build();
		gemini = provider("gemini");
		groq = provider("groq");
		gemini.warmUp(); // as the gateway does at startup, so a first call does not pay for building its client
		groq.warmUp();
	}

	@AfterEach
	void close() {
		chain.close();
	}

	private ChatProvider provider(String id) {
		return chain.registry().chain().stream().filter(slot -> slot.settings().id().equals(id)).findFirst().orElseThrow().provider();
	}

	@Test
	void geminiAnswersAndSendsTheKeyTheModelTheHistoryAndTheLimits() {
		FakeProviders.GEMINI.answerWith("hello from gemini");

		LlmAnswer answer = gemini.chat(REQUEST);

		assertThat(answer).isEqualTo(new LlmAnswer("hello from gemini", "gemini"));
		var request = FakeProviders.GEMINI.lastRequest();
		assertThat(request.getUrl()).contains("gemini-model").contains("generateContent");
		assertThat(request.getHeader("x-goog-api-key")).isEqualTo("gemini-key");
		String body = request.getBodyAsString();
		assertThat(body).contains("SYSTEM RULES").contains("earlier question").contains("earlier answer").contains("the final user message")
				.contains("\"maxOutputTokens\":777").contains("0.2");
		assertThat(body).doesNotContain("gemini-key");
		assertThat(body.indexOf("earlier question")).isLessThan(body.indexOf("the final user message"));
	}

	@Test
	void theOpenAiClientAnswersAtTheChatCompletionsPathWithTheKeyAsABearerToken() {
		FakeProviders.GROQ.answerWith("hello from groq");

		LlmAnswer answer = groq.chat(REQUEST);

		assertThat(answer).isEqualTo(new LlmAnswer("hello from groq", "groq"));
		var request = FakeProviders.GROQ.lastRequest();
		assertThat(request.getUrl()).isEqualTo("/chat/completions"); // appended to the base URL, which therefore ends in /v1 where the API needs it
		assertThat(request.getHeader("Authorization")).isEqualTo("Bearer groq-key");
		assertThat(request.getHeader("Content-Type")).startsWith("application/json");
		String body = request.getBodyAsString();
		assertThat(body).contains("\"model\":\"groq-model\"").contains("SYSTEM RULES").contains("earlier question").contains("earlier answer")
				.contains("the final user message").contains("\"max_tokens\":777").contains("\"reasoning_effort\":\"low\"")
				.contains("\"stream\":false").contains("\"role\":\"system\"");
		assertThat(body).doesNotContain("groq-key");
		assertThat(body.indexOf("earlier question")).isLessThan(body.indexOf("the final user message"));
	}

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({ "gemini,429,RATE_LIMITED", "gemini,500,UNAVAILABLE", "gemini,503,UNAVAILABLE", "gemini,401,BAD_KEY", "gemini,403,BAD_KEY",
			"gemini,400,OTHER", "groq,429,RATE_LIMITED", "groq,500,UNAVAILABLE", "groq,503,UNAVAILABLE", "groq,401,BAD_KEY", "groq,402,BAD_KEY",
			"groq,403,BAD_KEY", "groq,404,NOT_FOUND", "groq,408,TIMEOUT", "groq,400,OTHER" })
	void everyFailureIsClassifiedAndNoProviderBodyEscapes(String id, int status, Kind expected) {
		FakeProviders.Server server = id.equals("gemini") ? FakeProviders.GEMINI : FakeProviders.GROQ;
		ChatProvider chat = provider(id);
		server.failWith(status);

		assertThatThrownBy(() -> chat.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> {
			assertThat(ex.kind()).isEqualTo(expected);
			assertThat(ex.provider()).isEqualTo(id);
			assertThat(ex.getMessage()).doesNotContain("PROVIDER-ERROR-BODY").doesNotContain("key");
			if (!id.equals("gemini")) {
				assertThat(ex.getCause()).isNull(); // nothing of the response is kept (the Gemini SDK's own exception is, as before, never logged or returned)
			}
		});
		assertThat(server.requestCount()).isEqualTo(1); // never retried, not even inside the client
	}

	@Test
	void retryAfterIsReadFromTheHeader() {
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
		try (ProviderKit.Chain dead = ProviderKit.create().order("gemini", "groq")
				.set("archyra.ai.timeout.connect", "1s").set("archyra.ai.timeout.read", "1s")
				.setting("gemini", "protocol", "gemini").setting("gemini", "api-key", "k").setting("gemini", "model", "m")
				.setting("gemini", "base-url", "http://localhost:1").setting("groq", "api-key", "k2").setting("groq", "model", "m")
				.setting("groq", "base-url", "http://localhost:1").build()) {
			for (var slot : dead.registry().chain()) {
				assertThatThrownBy(() -> slot.provider().chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class,
						ex -> assertThat(ex.kind()).isIn(Kind.UNAVAILABLE, Kind.TIMEOUT));
			}
		}
	}

	@Test
	void theContextBudgetIsPerProvider() {
		assertThat(gemini.contextBudgetTokens()).isEqualTo(12000);
		assertThat(groq.contextBudgetTokens()).isEqualTo(4500);
		assertThat(gemini.displayName()).isEqualTo("GEMINI");
	}

	@Test
	void reasoningFieldsAreNeverReadAndAnAnswerThatIsOnlyReasoningIsEmpty() {
		FakeProviders.GROQ.answerWithRawBody("""
				{"choices":[{"index":0,"finish_reason":"length","message":{"role":"assistant","content":"","reasoning_content":"the answer is hidden in here","reasoning":"and here"}}]}""");

		assertThatThrownBy(() -> groq.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.kind()).isEqualTo(Kind.EMPTY));
	}

	@Test
	void reasoningFieldsAreIgnoredWhenThereIsAnAnswer() {
		FakeProviders.GROQ.answerWithRawBody("""
				{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"the real answer","reasoning_content":"hmm, thinking"}}]}""");

		assertThat(groq.chat(REQUEST).text()).isEqualTo("the real answer");
	}

	@Test
	void thinkBlocksAreStrippedFromBothKindsOfProvider() {
		FakeProviders.GROQ.answerWith("<think>first I consider [9]</think>\n\nThe answer [1].");
		FakeProviders.GEMINI.answerWith("<think>hidden</think>Gemini answer [1].");

		assertThat(groq.chat(REQUEST).text()).isEqualTo("The answer [1].");
		assertThat(gemini.chat(REQUEST).text()).isEqualTo("Gemini answer [1].");
	}

	@Test
	void anAnswerThatIsOnlyAnUnfinishedThoughtIsAFailure() {
		FakeProviders.GROQ.answerWith("<think>I am still thinking and never finish");

		assertThatThrownBy(() -> groq.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.kind()).isEqualTo(Kind.EMPTY));
	}

	@Test
	void aContentFilterFinishIsABlockedAnswer() {
		FakeProviders.GROQ.answerWithRawBody("""
				{"choices":[{"index":0,"finish_reason":"content_filter","message":{"role":"assistant","content":"partial"}}]}""");

		assertThatThrownBy(() -> groq.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.kind()).isEqualTo(Kind.EMPTY));
	}

	@Test
	void anErrorInsideA200BodyIsClassifiedLikeTheStatusItCarries() {
		FakeProviders.GROQ.answerWithRawBody("{\"error\":{\"code\":429,\"message\":\"PROVIDER-ERROR-BODY\"}}");
		assertThatThrownBy(() -> groq.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> {
			assertThat(ex.kind()).isEqualTo(Kind.RATE_LIMITED);
			assertThat(ex.getMessage()).doesNotContain("PROVIDER-ERROR-BODY");
		});

		FakeProviders.GROQ.answerWithRawBody("{\"error\":{\"code\":401,\"message\":\"x\"}}");
		assertThatThrownBy(() -> groq.chat(REQUEST)).isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.kind()).isEqualTo(Kind.BAD_KEY));
	}

	@Test
	void contentGivenAsAListOfPartsIsJoined() {
		FakeProviders.GROQ.answerWithRawBody("""
				{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":[{"type":"text","text":"part one "},{"type":"text","text":"part two"}]}}]}""");

		assertThat(groq.chat(REQUEST).text()).isEqualTo("part one part two");
	}

	@Test
	void extraBodyIsMergedButNeverOverridesTheModelOrTheMessages() {
		try (ProviderKit.Chain extra = ProviderKit.create().order("zai").openAi("zai", FakeProviders.ZAI)
				.setting("zai", "extra-body", "{\"thinking\":{\"type\":\"disabled\"},\"model\":\"evil\",\"messages\":[]}").build()) {
			FakeProviders.ZAI.reset();
			extra.registry().chain().getFirst().provider().chat(REQUEST);

			String body = FakeProviders.ZAI.lastRequest().getBodyAsString();
			assertThat(body).contains("\"thinking\":{\"type\":\"disabled\"}").contains("\"model\":\"zai-model\"").doesNotContain("evil");
		}
	}
}
