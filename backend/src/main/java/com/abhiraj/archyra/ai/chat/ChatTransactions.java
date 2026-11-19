package com.abhiraj.archyra.ai.chat;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.common.AiRuleException;
import com.abhiraj.archyra.usage.UsageService;
import com.abhiraj.archyra.usage.UsageType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The short transactions of a question, in the style of {@code AnalysisRegistrar}: nothing here is held while retrieval or
 * the model run.
 *
 * <ul>
 *   <li>{@link #reserve} (transaction A): lock the user's {@code AI_QUERY} row, charge one unit (a FREE user over the limit
 *       gets {@code USAGE_LIMIT_EXCEEDED}; PREMIUM is counted, never blocked), create the conversation if there is none,
 *       and insert the user message as PENDING with {@code quota_charged}. Charge and message commit together or not at all.</li>
 *   <li>{@link #complete} (transaction B): the assistant message, the user message ANSWERED, the conversation touched.</li>
 *   <li>{@link #refund} (transaction C): returns the unit exactly once, for a question that was never answered, and deletes
 *       the PENDING message (and a conversation that was created for it), so the history never holds an unanswered
 *       question.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ChatTransactions {

	/** The charged, still PENDING question. */
	record Reservation(UUID conversationId, UUID userMessageId, boolean createdConversation) {
	}

	/** What was stored for an answer. */
	record Saved(UUID conversationId, UUID assistantMessageId) {
	}

	private final ConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final UsageService usage;
	private final AiProperties properties;
	private final Clock clock;

	@Transactional
	Reservation reserve(UUID userId, UUID analysisId, UUID conversationId, String message) {
		usage.lock(userId, UsageType.AI_QUERY);
		usage.charge(userId, UsageType.AI_QUERY);
		Instant now = clock.instant();
		Conversation conversation = conversationFor(userId, analysisId, conversationId, message, now);
		ChatMessage question = messages.save(ChatMessage.user(conversation.getId(), message, MessageStatus.PENDING, true, now));
		return new Reservation(conversation.getId(), question.getId(), conversationId == null);
	}

	@Transactional
	Saved complete(Reservation reservation, String answer, String sourcesJson, String provider) {
		if (messages.markAnswered(reservation.userMessageId()) == 0) {
			throw new IllegalStateException("The question disappeared before its answer was stored");
		}
		Conversation conversation = conversations.findById(reservation.conversationId())
				.orElseThrow(() -> new IllegalStateException("The conversation disappeared before the answer was stored"));
		Instant now = clock.instant();
		ChatMessage reply = messages.save(ChatMessage.assistant(conversation.getId(), answer, sourcesJson, provider, false, now));
		conversation.touch(now);
		return new Saved(conversation.getId(), reply.getId());
	}

	/** A cache hit: both messages are stored, nothing is charged. */
	@Transactional
	Saved storeCached(UUID userId, UUID analysisId, UUID conversationId, String message, String answer, String sourcesJson, String provider) {
		Instant now = clock.instant();
		Conversation conversation = conversationFor(userId, analysisId, conversationId, message, now);
		messages.save(ChatMessage.user(conversation.getId(), message, MessageStatus.ANSWERED, false, now));
		ChatMessage reply = messages.save(ChatMessage.assistant(conversation.getId(), answer, sourcesJson, provider, true,
				now.plus(1, ChronoUnit.MILLIS)));
		conversation.touch(now.plus(1, ChronoUnit.MILLIS));
		return new Saved(conversation.getId(), reply.getId());
	}

	/**
	 * Returns the unit of a question that failed, once: only the caller that wins the guarded UPDATE releases it. Then the
	 * PENDING message goes, and the conversation too if it was created for this question and holds nothing else.
	 * Idempotent: a second call finds nothing to do.
	 */
	@Transactional
	void refund(UUID userId, UUID conversationId, UUID userMessageId, boolean deleteConversationIfEmpty) {
		if (messages.markQuotaRefunded(userMessageId) == 1) {
			usage.release(userId, UsageType.AI_QUERY);
		}
		messages.deletePending(userMessageId);
		if (deleteConversationIfEmpty && messages.countAll(conversationId) == 0) {
			conversations.deleteAllByIdIn(List.of(conversationId));
		}
	}

	private Conversation conversationFor(UUID userId, UUID analysisId, UUID conversationId, String message, Instant now) {
		if (conversationId == null) {
			if (conversations.countByAnalysisIdAndUserId(analysisId, userId) >= properties.limits().conversationsPerAnalysis()) {
				throw AiRuleException.tooManyConversations();
			}
			return conversations.save(Conversation.create(analysisId, userId, Conversation.titleOf(message), now));
		}
		Conversation conversation = conversations.findByIdAndUserId(conversationId, userId)
				.filter(found -> found.getAnalysisId().equals(analysisId))
				.orElseThrow(AiRuleException::conversationNotFound);
		if (Conversation.DEFAULT_TITLE.equals(conversation.getTitle()) && messages.countVisible(conversationId) == 0) {
			conversation.retitle(Conversation.titleOf(message)); // the title comes from the first question
		}
		return conversation;
	}
}
