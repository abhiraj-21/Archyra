package com.abhiraj.archyra.ai.chat;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A question that is still PENDING at startup was charged but never answered (the server stopped between the reservation
 * and the answer). Like {@code AnalysisRecovery}, it gets its unit back and is removed. The refund is idempotent, so
 * running this again is always safe. Single instance assumed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ChatRecovery {

	private final ChatMessageRepository messages;
	private final ConversationRepository conversations;
	private final ChatTransactions transactions;

	@EventListener(ApplicationReadyEvent.class)
	void onStartup() {
		recoverPending();
	}

	/** Refunds and removes every PENDING question; returns how many there were. */
	int recoverPending() {
		int recovered = 0;
		for (ChatMessage pending : messages.findPending()) {
			try {
				Conversation conversation = conversations.findById(pending.getConversationId()).orElse(null);
				if (conversation == null) {
					messages.deletePending(pending.getId()); // nobody to refund: the conversation is gone
					log.error("Question {} was pending in a conversation that no longer exists", pending.getId());
				}
				else {
					transactions.refund(conversation.getUserId(), conversation.getId(), pending.getId(), true);
				}
				recovered++;
			}
			catch (RuntimeException ex) {
				log.error("Could not recover pending question {}", pending.getId(), ex);
			}
		}
		if (recovered > 0) {
			log.warn("Returned the quota of {} questions that were interrupted by a restart", recovered);
		}
		return recovered;
	}
}
