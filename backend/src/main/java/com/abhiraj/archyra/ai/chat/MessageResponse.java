package com.abhiraj.archyra.ai.chat;

import java.time.Instant;
import java.util.List;

import com.abhiraj.archyra.ai.llm.ProviderId;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A message of a conversation. {@code sources} and {@code provider} exist only on assistant messages. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageResponse(String id, MessageRole role, String content, List<ChatSource> sources, ProviderId provider,
		boolean cached, Instant createdAt) {
}
