package com.abhiraj.archyra.ai.llm;

import java.util.List;

/**
 * What one model call carries: the rules, the conversation so far, and the final user message (the retrieved context
 * and the question). Plain text only, never logged.
 */
public record LlmRequest(String system, List<ChatTurn> history, String userMessage) {

	public LlmRequest {
		history = List.copyOf(history);
	}
}
