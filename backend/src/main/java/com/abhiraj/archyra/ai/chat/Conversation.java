package com.abhiraj.archyra.ai.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A conversation about one analysis, owned by the user who started it ({@code conversations}). Plain ids, no foreign keys:
 * the analysis and the user belong to other features. Deleted with its messages, and with the analysis.
 */
@Entity
@DynamicUpdate
@Table(name = "conversations",
		indexes = @Index(name = "idx_conversation_analysis_user_updated", columnList = "analysis_id, user_id, updated_at desc"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Conversation {

	static final int TITLE_LENGTH = 120;
	static final String DEFAULT_TITLE = "New conversation";

	@Id
	private UUID id;

	@Column(name = "analysis_id", nullable = false, updatable = false)
	private UUID analysisId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(nullable = false, length = TITLE_LENGTH)
	private String title;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	static Conversation create(UUID analysisId, UUID userId, String title, Instant now) {
		Conversation conversation = new Conversation();
		conversation.id = UUID.randomUUID();
		conversation.analysisId = analysisId;
		conversation.userId = userId;
		conversation.title = title;
		conversation.createdAt = now;
		conversation.updatedAt = now;
		return conversation;
	}

	void retitle(String title) {
		this.title = title;
	}

	void touch(Instant now) {
		this.updatedAt = now;
	}

	/** The first words of the first question: at most 60 characters, cut at a word. */
	static String titleOf(String message) {
		String text = message.strip().replaceAll("\\s+", " ");
		if (text.length() <= 60) {
			return text;
		}
		int cut = text.lastIndexOf(' ', 60);
		return (cut > 20 ? text.substring(0, cut) : text.substring(0, 60)).strip();
	}
}
