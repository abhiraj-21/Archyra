package com.abhiraj.archyra.ai.chat;

import java.util.Optional;
import java.util.UUID;

/** Accepts only the canonical UUID form, so loose inputs such as {@code 1-1-1-1-1} are never ids. */
final class ConversationIds {

	private ConversationIds() {
	}

	static Optional<UUID> parse(String text) {
		if (text == null) {
			return Optional.empty();
		}
		try {
			UUID id = UUID.fromString(text);
			return id.toString().equalsIgnoreCase(text) ? Optional.of(id) : Optional.empty();
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}
}
