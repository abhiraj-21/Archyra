package com.abhiraj.archyra.ai.chat;

import java.time.Instant;

/** A conversation in a list. {@code messageCount} counts the questions that were answered and their answers. */
public record ConversationSummary(String id, String title, Instant createdAt, Instant updatedAt, long messageCount) {
}
