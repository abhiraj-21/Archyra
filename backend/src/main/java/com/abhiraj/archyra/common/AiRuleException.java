package com.abhiraj.archyra.common;

import org.springframework.http.HttpStatus;

/**
 * An Ask AI request the rules refuse. Carries its own error code and status; the message is fixed text and never
 * echoes user input, a provider's response or a key.
 */
public class AiRuleException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	private AiRuleException(String code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	/** 409: the index of the analysis is not READY (the client reads {@code GET ai-index} for the state). */
	public static AiRuleException notReady() {
		return new AiRuleException("AI_NOT_READY", HttpStatus.CONFLICT,
				"Ask AI is not ready for this analysis yet. Check its index status.");
	}

	/** 409: an index that cannot be built again from what is stored. */
	public static AiRuleException reanalyzeRequired() {
		return new AiRuleException("AI_REANALYZE_REQUIRED", HttpStatus.CONFLICT,
				"Re-analyze this repository to enable Ask AI.");
	}

	/** 404: not yours, belongs to another analysis, deleted, or malformed in the URL. */
	public static AiRuleException conversationNotFound() {
		return new AiRuleException("CONVERSATION_NOT_FOUND", HttpStatus.NOT_FOUND, "Conversation not found");
	}

	/** 409: {@code archyra.ai.limits.messages-per-conversation} reached. */
	public static AiRuleException conversationFull() {
		return new AiRuleException("CONVERSATION_FULL", HttpStatus.CONFLICT,
				"This conversation is full. Start a new conversation to keep asking.");
	}

	/** 409: a question of this conversation is still being answered. */
	public static AiRuleException conversationBusy() {
		return new AiRuleException("CONVERSATION_BUSY", HttpStatus.CONFLICT,
				"A question in this conversation is still being answered. Wait for it to finish.");
	}

	/** 409: {@code archyra.ai.limits.conversations-per-analysis} reached. */
	public static AiRuleException tooManyConversations() {
		return new AiRuleException("TOO_MANY_CONVERSATIONS", HttpStatus.CONFLICT,
				"This repository has too many conversations. Delete one to start another.");
	}

	/** 503: no provider could answer. The question was not counted. */
	public static AiRuleException unavailable() {
		return new AiRuleException("AI_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
				"Ask AI is not available right now. Your question was not counted; please try again later.");
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}
}
