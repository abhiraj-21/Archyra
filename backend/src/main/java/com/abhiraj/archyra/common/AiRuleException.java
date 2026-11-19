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

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}
}
