package com.abhiraj.archyra.ai.chat;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One message of a conversation ({@code chat_messages}). {@code @DynamicUpdate}: the quota flags of a user message must
 * never be overwritten by a stale full-row save; the refund has its own guarded UPDATE.
 */
@Entity
@DynamicUpdate
@Table(name = "chat_messages", indexes = @Index(name = "idx_chat_message_conversation", columnList = "conversation_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ChatMessage {

	@Id
	private UUID id;

	@Column(name = "conversation_id", nullable = false, updatable = false)
	private UUID conversationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 10)
	private MessageRole role;

	@Column(nullable = false, updatable = false, columnDefinition = "text")
	private String content;

	/** JSON text of the sources of an assistant message, stored as {@code jsonb}; null for a user message. */
	@Column(name = "sources", columnDefinition = "jsonb")
	@ColumnTransformer(read = "sources::text", write = "cast(? as jsonb)")
	private String sourcesJson;

	/** The id of the provider that answered, free-form (a provider is a configuration block, not an enum). */
	@Column(length = 40, updatable = false)
	private String provider;

	@Column(nullable = false, updatable = false, columnDefinition = "boolean not null default false")
	private boolean cached;

	/** Of a user message only. */
	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private MessageStatus status;

	@Column(name = "quota_charged", nullable = false, updatable = false, columnDefinition = "boolean not null default false")
	private boolean quotaCharged;

	/**
	 * The weekly window the charged unit belongs to (what {@code UsageService.charge} returned), so a refund after the
	 * reset is known to be a no-op. Null for an uncharged message and for one charged before the allowance became weekly.
	 */
	@Column(name = "quota_window_start", updatable = false)
	private Instant quotaWindowStart;

	/** Set by one guarded UPDATE, which is what makes a refund happen only once. */
	@Column(name = "quota_refunded", nullable = false, columnDefinition = "boolean not null default false")
	private boolean quotaRefunded;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	static ChatMessage user(UUID conversationId, String content, MessageStatus status, boolean quotaCharged, Instant quotaWindowStart,
			Instant now) {
		ChatMessage message = new ChatMessage();
		message.id = UUID.randomUUID();
		message.conversationId = conversationId;
		message.role = MessageRole.USER;
		message.content = content;
		message.status = status;
		message.quotaCharged = quotaCharged;
		message.quotaWindowStart = quotaWindowStart;
		message.createdAt = now;
		return message;
	}

	static ChatMessage assistant(UUID conversationId, String content, String sourcesJson, String provider, boolean cached, Instant now) {
		ChatMessage message = new ChatMessage();
		message.id = UUID.randomUUID();
		message.conversationId = conversationId;
		message.role = MessageRole.ASSISTANT;
		message.content = content;
		message.sourcesJson = sourcesJson;
		message.provider = provider;
		message.cached = cached;
		message.createdAt = now;
		return message;
	}
}
