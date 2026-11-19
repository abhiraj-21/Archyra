package com.abhiraj.archyra.ai.chat;

import java.util.List;

import com.abhiraj.archyra.ai.llm.ProviderId;

/**
 * Response of {@code POST /api/v1/analyses/{analysisId}/chat}. The {@code ref} of a source is the number of a
 * {@code [n]} marker in {@code answer}, already renumbered by first appearance.
 *
 * @param messageId the assistant message
 * @param provider  who answered; for a cached answer, who answered when it was first given
 */
public record ChatAnswer(String conversationId, String messageId, String answer, List<ChatSource> sources, boolean cached,
		ProviderId provider) {
}
