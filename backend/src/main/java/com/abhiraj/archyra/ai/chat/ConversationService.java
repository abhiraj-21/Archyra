package com.abhiraj.archyra.ai.chat;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.llm.ProviderRegistry;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.common.AiRuleException;
import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/**
 * Conversations and their messages. A conversation belongs to the user who created it: everyone else, administrators
 * included, gets {@code 404 CONVERSATION_NOT_FOUND}, the same as for an unknown or malformed id. The analysis is checked
 * through {@link AnalysisService#requireCompleted}, like every read endpoint.
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

	static final int DEFAULT_PAGE_SIZE = 50;
	static final int MAX_PAGE_SIZE = 200;

	private final AnalysisService analyses;
	private final ConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final ChatTransactions transactions;
	private final ChatInFlight inFlight;
	private final ProviderRegistry providers;
	private final AiProperties properties;
	private final Clock clock;

	@Transactional(readOnly = true)
	public PageOf<ConversationSummary> list(String analysisIdText, int page, int size) {
		checkPaging(page, size);
		UUID userId = caller();
		UUID analysisId = analyses.requireCompleted(analysisIdText);
		Page<Conversation> found = conversations.findByAnalysisIdAndUserId(analysisId, userId,
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("id"))));
		Map<UUID, Long> counts = new HashMap<>();
		if (!found.isEmpty()) {
			messages.countVisibleByConversation(found.getContent().stream().map(Conversation::getId).toList())
					.forEach(count -> counts.put(count.getConversationId(), count.getTotal()));
		}
		return new PageOf<>(found.getContent().stream().map(conversation -> summary(conversation, counts.getOrDefault(conversation.getId(), 0L))).toList(),
				page, size, found.getTotalElements(), found.getTotalPages());
	}

	@Transactional
	public ConversationSummary create(String analysisIdText) {
		UUID userId = caller();
		UUID analysisId = analyses.requireCompleted(analysisIdText);
		if (conversations.countByAnalysisIdAndUserId(analysisId, userId) >= properties.limits().conversationsPerAnalysis()) {
			throw AiRuleException.tooManyConversations();
		}
		Conversation created = conversations.save(Conversation.create(analysisId, userId, Conversation.DEFAULT_TITLE, clock.instant()));
		return summary(created, 0);
	}

	/** Deletes a conversation with its messages. One that has a question being answered is refused: the answer would have nowhere to go. */
	@Transactional
	public void delete(String conversationIdText) {
		Conversation conversation = owned(conversationIdText);
		if (inFlight.isBusy(conversation.getId())) {
			throw AiRuleException.conversationBusy();
		}
		messages.deleteByConversationId(conversation.getId());
		conversations.deleteAllByIdIn(List.of(conversation.getId()));
	}

	@Transactional(readOnly = true)
	public PageOf<MessageResponse> messages(String conversationIdText, int page, int size) {
		checkPaging(page, size);
		Conversation conversation = owned(conversationIdText);
		Page<ChatMessage> found = messages.findVisible(conversation.getId(), PageRequest.of(page, size));
		return new PageOf<>(found.getContent().stream().map(this::response).toList(), page, size, found.getTotalElements(),
				found.getTotalPages());
	}

	/**
	 * Removes every conversation and message of an analysis (it is being deleted or has failed). A question that was still
	 * being answered gets its quota unit back first, so deleting cannot cost anyone a question. Idempotent.
	 */
	@Transactional
	public void purge(UUID analysisId) {
		List<Conversation> found = conversations.findByAnalysisId(analysisId);
		if (found.isEmpty()) {
			return;
		}
		Map<UUID, UUID> userOf = found.stream().collect(Collectors.toMap(Conversation::getId, Conversation::getUserId));
		List<UUID> ids = List.copyOf(userOf.keySet());
		for (ChatMessage pending : messages.findPendingIn(ids)) {
			transactions.refund(userOf.get(pending.getConversationId()), pending.getConversationId(), pending.getId(), false);
		}
		messages.deleteByConversationIdIn(ids);
		conversations.deleteAllByIdIn(ids);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private Conversation owned(String conversationIdText) {
		UUID userId = caller();
		return ConversationIds.parse(conversationIdText).flatMap(id -> conversations.findByIdAndUserId(id, userId))
				.orElseThrow(AiRuleException::conversationNotFound);
	}

	private static UUID caller() {
		return CurrentUser.id().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No signed-in user"));
	}

	private static void checkPaging(int page, int size) {
		if (page < 0) {
			throw new InvalidRequestException("page must not be negative");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
	}

	private static ConversationSummary summary(Conversation conversation, long messageCount) {
		return new ConversationSummary(conversation.getId().toString(), conversation.getTitle(), conversation.getCreatedAt(),
				conversation.getUpdatedAt(), messageCount);
	}

	private MessageResponse response(ChatMessage message) {
		boolean assistant = message.getRole() == MessageRole.ASSISTANT;
		return new MessageResponse(message.getId().toString(), message.getRole(), message.getContent(),
				assistant ? SourcesJson.read(message.getSourcesJson()) : null, assistant ? message.getProvider() : null,
				assistant ? providers.displayName(message.getProvider()) : null,
				message.isCached(), message.getCreatedAt());
	}
}
