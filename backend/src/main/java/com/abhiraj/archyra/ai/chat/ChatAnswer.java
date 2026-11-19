package com.abhiraj.archyra.ai.chat;

import java.util.List;

/**
 * Response of {@code POST /api/v1/analyses/{analysisId}/chat}. The {@code ref} of a source is the number of a
 * {@code [n]} marker in {@code answer}, already renumbered by first appearance.
 *
 * @param messageId the assistant message
 * @param provider  the id of the provider that answered (for a cached answer, who answered when it was first given)
 * @param providerName its display name
 */
public record ChatAnswer(String conversationId, String messageId, String answer, List<ChatSource> sources, boolean cached,
		String provider, String providerName) {
}
