package com.abhiraj.archyra.ai.chat;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A message of a conversation. {@code sources}, {@code provider} (the id) and {@code providerName} exist only on assistant messages. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageResponse(String id, MessageRole role, String content, List<ChatSource> sources, String provider, String providerName,
		boolean cached, Instant createdAt) {
}
