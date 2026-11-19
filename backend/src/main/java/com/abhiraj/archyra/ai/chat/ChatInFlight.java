package com.abhiraj.archyra.ai.chat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** The conversations that have a question being answered right now (single instance, in memory): one at a time per conversation. */
@Component
class ChatInFlight {

	private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

	/** False if the conversation is already busy. */
	boolean tryEnter(UUID conversationId) {
		return busy.add(conversationId);
	}

	void leave(UUID conversationId) {
		busy.remove(conversationId);
	}

	boolean isBusy(UUID conversationId) {
		return busy.contains(conversationId);
	}
}
