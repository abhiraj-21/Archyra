package com.abhiraj.archyra.ai.chat;

/** Request body of {@code POST /api/v1/analyses/{analysisId}/chat}. */
public record ChatQuestion(String message, String conversationId) {
}
