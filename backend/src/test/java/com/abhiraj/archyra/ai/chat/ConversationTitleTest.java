package com.abhiraj.archyra.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationTitleTest {

	@Test
	void aShortQuestionIsItsOwnTitle() {
		assertThat(Conversation.titleOf("How does authentication work?")).isEqualTo("How does authentication work?");
	}

	@Test
	void aLongQuestionIsCutAtAWordWithinSixtyCharacters() {
		String title = Conversation.titleOf("Please explain in detail how the placement of an order works from the controller down");

		assertThat(title).isEqualTo("Please explain in detail how the placement of an order works");
		assertThat(title.length()).isLessThanOrEqualTo(60);
	}

	@Test
	void whitespaceIsCollapsedAndOneVeryLongWordIsCutHard() {
		assertThat(Conversation.titleOf("  what\n\n is   this ")).isEqualTo("what is this");
		assertThat(Conversation.titleOf("x".repeat(200))).hasSize(60);
	}

	@Test
	void theTitleAlwaysFitsTheColumn() {
		assertThat(Conversation.titleOf("word ".repeat(100)).length()).isLessThanOrEqualTo(Conversation.TITLE_LENGTH);
	}
}
