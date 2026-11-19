package com.abhiraj.archyra.ai.llm;

/** One earlier message of the conversation, as it goes back to the model (never with its sources). */
public record ChatTurn(Role role, String content) {

	public enum Role {
		USER, ASSISTANT
	}
}
