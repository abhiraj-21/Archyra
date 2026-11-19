package com.abhiraj.archyra.ai.chat;

/** Of a user message: asked and charged but not answered yet, or answered. A PENDING message is never listed and never part of the history. */
public enum MessageStatus {
	PENDING, ANSWERED
}
