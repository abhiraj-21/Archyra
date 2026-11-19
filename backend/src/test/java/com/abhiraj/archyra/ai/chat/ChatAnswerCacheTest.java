package com.abhiraj.archyra.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.llm.ChatTurn;

class ChatAnswerCacheTest {

	private static final UUID ANALYSIS = UUID.randomUUID();
	private static final List<ChatTurn> NO_HISTORY = List.of();

	private static String key(String message, List<ChatTurn> history, String version) {
		return ChatAnswerCache.key(ANALYSIS, message, history, version);
	}

	@Test
	void caseAndWhitespaceDoNotMakeADifferentQuestion() {
		assertThat(key("How does   Auth work?", NO_HISTORY, "v1")).isEqualTo(key("  how does auth\nwork? ", NO_HISTORY, "v1"));
	}

	@Test
	void theAnalysisTheHistoryAndTheIndexVersionAreAllPartOfTheKey() {
		String base = key("q", NO_HISTORY, "v1");

		assertThat(ChatAnswerCache.key(UUID.randomUUID(), "q", NO_HISTORY, "v1")).isNotEqualTo(base);
		assertThat(key("q", NO_HISTORY, "v2")).isNotEqualTo(base);
		assertThat(key("q", List.of(new ChatTurn(ChatTurn.Role.USER, "earlier")), "v1")).isNotEqualTo(base);
		assertThat(key("another", NO_HISTORY, "v1")).isNotEqualTo(base);
	}

	@Test
	void historyTurnsAreDistinguishedByRoleAndContent() {
		String user = key("q", List.of(new ChatTurn(ChatTurn.Role.USER, "x")), "v");
		String assistant = key("q", List.of(new ChatTurn(ChatTurn.Role.ASSISTANT, "x")), "v");

		assertThat(user).isNotEqualTo(assistant);
		assertThat(key("q", List.of(new ChatTurn(ChatTurn.Role.USER, "a"), new ChatTurn(ChatTurn.Role.USER, "b")), "v"))
				.isNotEqualTo(key("q", List.of(new ChatTurn(ChatTurn.Role.USER, "ab")), "v"));
	}

	@Test
	void theKeyIsAHashAndNeverContainsTheQuestion() {
		assertThat(key("a secret question", NO_HISTORY, "v")).hasSize(64).matches("[0-9a-f]+").doesNotContain("secret");
	}

	@Test
	void entriesAreStoredAndExpire() throws Exception {
		AiProperties properties = new AiProperties(null, null, null, null, null, null, null,
				new AiProperties.Cache(Duration.ofMillis(150), 10), null);
		ChatAnswerCache cache = new ChatAnswerCache(properties);
		cache.put("k", new ChatAnswerCache.Entry("answer", List.of(), "gemini"));

		assertThat(cache.get("k")).isPresent().get().extracting(ChatAnswerCache.Entry::answer).isEqualTo("answer");
		assertThat(cache.get("other")).isEmpty();
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> cache.get("k").isEmpty());
	}
}
