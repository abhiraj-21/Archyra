package com.abhiraj.archyra.ai.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.index.AiIndexService;
import com.abhiraj.archyra.ai.llm.AllProvidersFailedException;
import com.abhiraj.archyra.ai.llm.ChatTurn;
import com.abhiraj.archyra.ai.llm.ProviderGateway;
import com.abhiraj.archyra.ai.llm.ProviderRegistry;
import com.abhiraj.archyra.ai.retrieval.CodeItem;
import com.abhiraj.archyra.ai.retrieval.ContextItem;
import com.abhiraj.archyra.ai.retrieval.ProjectContext;
import com.abhiraj.archyra.ai.retrieval.QuestionIntent;
import com.abhiraj.archyra.ai.retrieval.QuestionIntents;
import com.abhiraj.archyra.ai.retrieval.RetrievalRequest;
import com.abhiraj.archyra.ai.retrieval.RetrievalService;
import com.abhiraj.archyra.ai.retrieval.RetrievedContext;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.common.AiRuleException;
import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.common.RateLimitedException;
import com.abhiraj.archyra.common.ServiceBusyException;
import com.abhiraj.archyra.graph.NodeInfo;
import com.abhiraj.archyra.graph.RetrievalGraphService;
import com.abhiraj.archyra.security.CurrentUser;
import com.abhiraj.archyra.security.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code POST /analyses/{id}/chat}: a repository-aware question, in the order that matters.
 *
 * <ol>
 *   <li><b>Validate</b>, with no side effects: the message, ownership and completion of the analysis, a READY index, the
 *       conversation (yours, this analysis, room left), the rate limit, one question at a time per conversation, and a
 *       provider that is configured and not cooling down. Anything refused here costs nothing.</li>
 *   <li><b>Cache</b>: a hit stores both messages and costs nothing. The key includes the question's intent (overview or specific).</li>
 *   <li><b>Retrieve</b>, still free. A question with no usable context in this repository is refused with
 *       {@code 422 QUESTION_NOT_ANSWERABLE}: never sent to a provider, not counted, nothing stored.</li>
 *   <li><b>Reserve</b> one quota unit and the PENDING question (a short transaction).</li>
 *   <li><b>Build the prompt per provider</b>, <b>call the model</b> through the gateway, <b>ground</b> the citations and verify
 *       every source in Neo4j. No transaction is open meanwhile.</li>
 *   <li><b>Persist</b> the answer.</li>
 *   <li>Any failure after the reservation <b>refunds</b> exactly once and removes the PENDING question.</li>
 * </ol>
 *
 * Never logs the question, code, answers or keys: only user id, analysis id, provider, latency, outcome, cached.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

	private final AnalysisService analyses;
	private final AiIndexService indexes;
	private final ConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final ChatTransactions transactions;
	private final ChatInFlight inFlight;
	private final ChatAnswerCache cache;
	private final ProviderGateway gateway;
	private final ProviderRegistry providers;
	private final RetrievalService retrieval;
	private final RetrievalGraphService graph;
	private final ProjectContext project;
	private final PromptBuilder prompts;
	private final RateLimiter rateLimiter;
	private final AiProperties properties;
	private final com.abhiraj.archyra.analysis.AnalysisActivity activity;

	public ChatAnswer ask(String analysisIdText, ChatQuestion question) {
		long started = System.nanoTime();
		UUID userId = CurrentUser.id().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No signed-in user"));
		UUID analysisId = null;
		String outcome = "ERROR";
		boolean cached = false;
		String provider = null;
		try {
			// 1. validate: nothing here has a side effect
			String message = validMessage(question.message());
			UUID conversationId = conversationId(question.conversationId());
			analysisId = analyses.requireCompleted(analysisIdText);
			indexes.requireReady(analysisId);
			Conversation conversation = conversationId == null ? null : ownedConversation(conversationId, analysisId, userId);
			if (conversation != null && messages.countVisible(conversationId) + 2 > properties.limits().messagesPerConversation()) {
				throw AiRuleException.conversationFull();
			}
			RateLimiter.Decision decision = rateLimiter.tryConsume(RateLimiter.Rule.AI_CHAT, userId.toString());
			if (!decision.allowed()) {
				throw new RateLimitedException(decision.retryAfterSeconds());
			}
			if (conversationId != null && !inFlight.tryEnter(conversationId)) {
				throw AiRuleException.conversationBusy();
			}
			try {
				if (!gateway.anyUsable()) {
					throw AiRuleException.unavailable(); // before anything is charged
				}
				List<ChatMessage> newest = conversationId == null ? List.of() : newestVisible(conversationId);
				List<ChatTurn> history = history(newest);

				// 2. cache
				QuestionIntent intent = QuestionIntents.classify(message);
				String key = ChatAnswerCache.key(analysisId, message, history, String.valueOf(indexes.describe(analysisId).indexedAt()), intent);
				Optional<ChatAnswerCache.Entry> hit = cache.get(key);
				if (hit.isPresent()) {
					ChatAnswerCache.Entry entry = hit.get();
					ChatTransactions.Saved saved = storeCached(userId, analysisId, conversationId, message, entry);
					outcome = "OK";
					cached = true;
					activity.touch(analysisId); // a free cached answer is a use of the repository too
					provider = entry.provider();
					return new ChatAnswer(saved.conversationId().toString(), saved.assistantMessageId().toString(), entry.answer(),
							entry.sources(), true, entry.provider(), providers.displayName(entry.provider()));
				}

				// 3. retrieve: still free. A question that nothing in this repository relates to stops here (422), is never sent to a
				// provider, is not counted and is not stored; the rate limit above was consumed all the same.
				RetrievedContext context = retrieve(analysisId, message, newest);
				if (!context.usable()) {
					throw AiRuleException.questionNotAnswerable();
				}

				// 4. reserve one unit and the PENDING question; everything from here on refunds exactly once if it fails
				ChatTransactions.Reservation reservation = reserve(userId, analysisId, conversationId, message);
				ChatAnswer answer;
				try {
					// 5. prompt per provider, call, ground
					Generated generated = generate(analysisId, message, context, history);
					provider = generated.provider();
					// 6. persist
					ChatTransactions.Saved saved = transactions.complete(reservation, generated.text(),
							SourcesJson.write(generated.sources()), generated.provider());
					answer = new ChatAnswer(saved.conversationId().toString(), saved.assistantMessageId().toString(), generated.text(),
							generated.sources(), false, generated.provider(), providers.displayName(generated.provider()));
				}
				catch (Throwable failure) {
					// refund once, drop the PENDING question
					refund(userId, reservation);
					if (failure instanceof AllProvidersFailedException) {
						throw AiRuleException.unavailable();
					}
					throw failure;
				}
				cache.put(key, new ChatAnswerCache.Entry(answer.answer(), answer.sources(), answer.provider()));
				activity.touch(analysisId); // asked about: the repository was used
				outcome = "OK";
				return answer;
			}
			finally {
				if (conversationId != null) {
					inFlight.leave(conversationId);
				}
			}
		}
		catch (RuntimeException refused) {
			outcome = refused instanceof AiRuleException rule ? rule.code() : refused.getClass().getSimpleName();
			throw refused;
		}
		finally {
			log.info("Ask AI user={} analysis={} provider={} latencyMs={} outcome={} cached={}", userId, analysisId, provider,
					(System.nanoTime() - started) / 1_000_000, outcome, cached);
		}
	}

	// ---- steps ---------------------------------------------------------------------------------

	private record Generated(String text, List<ChatSource> sources, String provider) {
	}

	/** What to show the model for the question (no quota is involved, no transaction is open). */
	private RetrievedContext retrieve(UUID analysisId, String message, List<ChatMessage> newest) {
		String previousQuestion = newest.stream().filter(m -> m.getRole() == MessageRole.USER).map(ChatMessage::getContent).findFirst().orElse(null);
		List<String> sticky = newest.stream().filter(m -> m.getRole() == MessageRole.ASSISTANT).findFirst()
				.map(m -> SourcesJson.read(m.getSourcesJson()).stream().map(ChatSource::id).toList()).orElse(List.of());
		return retrieval.retrieve(new RetrievalRequest(analysisId, message, previousQuestion, sticky));
	}

	private Generated generate(UUID analysisId, String message, RetrievedContext context, List<ChatTurn> history) {
		String preamble = project.preamble(analysisId);
		ProviderGateway.Result<PromptBuilder.Built> result = gateway.ask(provider -> {
			PromptBuilder.Built built = prompts.build(provider.contextBudgetTokens(), context, history, message, preamble);
			return new ProviderGateway.Prepared<>(built.request(), built);
		});
		List<ContextItem> items = result.attachment().items();
		String answer = result.answer().text();

		// ground: ignore numbers that are not context items, verify every node in Neo4j, renumber by first appearance
		List<Integer> candidates = Grounding.citedIndexes(answer, items.size());
		Set<String> candidateNodes = candidates.stream().flatMap(index -> items.get(index).nodeIds().stream()).collect(Collectors.toSet());
		Map<String, NodeInfo> verified = candidateNodes.isEmpty() ? Map.of() : graph.citableNodes(analysisId.toString(), candidateNodes);
		Set<Integer> accepted = candidates.stream()
				.filter(index -> items.get(index).nodeIds().stream().anyMatch(verified::containsKey)).collect(Collectors.toSet());
		Grounding.Outcome grounded = Grounding.ground(answer, items, accepted::contains);
		return new Generated(grounded.text(), sources(grounded.cited(), verified), result.answer().provider());
	}

	/** One source per verified node, by the first item that cited it; a node's excerpt comes from the code item that showed it. */
	private static List<ChatSource> sources(List<ContextItem> cited, Map<String, NodeInfo> verified) {
		Map<String, ChatSource> byNode = new LinkedHashMap<>();
		for (int i = 0; i < cited.size(); i++) {
			ContextItem item = cited.get(i);
			String excerpt = item instanceof CodeItem code ? excerpt(code.text()) : null;
			for (String nodeId : item.nodeIds()) {
				NodeInfo node = verified.get(nodeId);
				if (node == null) {
					continue;
				}
				ChatSource existing = byNode.get(nodeId);
				if (existing == null) {
					byNode.put(nodeId, new ChatSource(i + 1, node.type(), node.id(), node.name(), node.file(), node.line(), node.endLine(), excerpt));
				}
				else if (existing.excerpt() == null && excerpt != null) {
					byNode.put(nodeId, new ChatSource(existing.ref(), existing.type(), existing.id(), existing.name(), existing.file(),
							existing.line(), existing.endLine(), excerpt));
				}
			}
		}
		return List.copyOf(byNode.values());
	}

	/** The first few lines of the chunk. */
	static String excerpt(String text) {
		String[] lines = text.split("\n", EXCERPT_LINES + 1);
		String head = String.join("\n", java.util.Arrays.copyOf(lines, Math.min(lines.length, EXCERPT_LINES)));
		return head.length() > EXCERPT_CHARS ? head.substring(0, EXCERPT_CHARS) : head;
	}

	private static final int EXCERPT_LINES = 8;
	private static final int EXCERPT_CHARS = 800;

	private ChatTransactions.Reservation reserve(UUID userId, UUID analysisId, UUID conversationId, String message) {
		try {
			return transactions.reserve(userId, analysisId, conversationId, message);
		}
		catch (PessimisticLockingFailureException ex) {
			log.warn("Gave up waiting for the usage lock of a user: {}", ex.getClass().getSimpleName());
			throw new ServiceBusyException("Too many requests are being processed for your account. Please try again.");
		}
	}

	private ChatTransactions.Saved storeCached(UUID userId, UUID analysisId, UUID conversationId, String message, ChatAnswerCache.Entry entry) {
		return transactions.storeCached(userId, analysisId, conversationId, message, entry.answer(), SourcesJson.write(entry.sources()),
				entry.provider());
	}

	private void refund(UUID userId, ChatTransactions.Reservation reservation) {
		try {
			transactions.refund(userId, reservation.conversationId(), reservation.userMessageId(), reservation.createdConversation());
		}
		catch (RuntimeException ex) {
			log.error("Could not refund the quota unit of question {}; startup recovery will", reservation.userMessageId(), ex);
		}
	}

	// ---- helpers -------------------------------------------------------------------------------

	private String validMessage(String raw) {
		String message = raw == null ? "" : raw.strip();
		int max = properties.limits().messageMaxChars();
		if (message.isEmpty() || message.length() > max) {
			throw new InvalidRequestException("message must be between 1 and " + max + " characters");
		}
		return message;
	}

	private static UUID conversationId(String text) {
		if (text == null) {
			return null;
		}
		return ConversationIds.parse(text).orElseThrow(() -> new InvalidRequestException("conversationId is not a valid id"));
	}

	private Conversation ownedConversation(UUID conversationId, UUID analysisId, UUID userId) {
		return conversations.findByIdAndUserId(conversationId, userId).filter(found -> found.getAnalysisId().equals(analysisId))
				.orElseThrow(AiRuleException::conversationNotFound);
	}

	private List<ChatMessage> newestVisible(UUID conversationId) {
		int size = Math.max(properties.history().turns() * 2, 2);
		return messages.findNewestVisible(conversationId, PageRequest.of(0, size));
	}

	/** The last few turns, oldest first, starting with a question, within the history token cap; without sources. */
	private List<ChatTurn> history(List<ChatMessage> newest) {
		int turns = properties.history().turns();
		if (turns == 0 || newest.isEmpty()) {
			return List.of();
		}
		List<ChatMessage> chronological = new ArrayList<>(newest.subList(0, Math.min(newest.size(), turns * 2)));
		java.util.Collections.reverse(chronological);
		while (!chronological.isEmpty() && chronological.getFirst().getRole() != MessageRole.USER) {
			chronological.removeFirst();
		}
		while (!chronological.isEmpty() && tokens(chronological) > properties.history().maxTokens()) {
			chronological.removeFirst(); // the oldest question
			if (!chronological.isEmpty() && chronological.getFirst().getRole() == MessageRole.ASSISTANT) {
				chronological.removeFirst(); // and its answer
			}
		}
		return chronological.stream()
				.map(m -> new ChatTurn(m.getRole() == MessageRole.USER ? ChatTurn.Role.USER : ChatTurn.Role.ASSISTANT, m.getContent())).toList();
	}

	private static int tokens(List<ChatMessage> history) {
		return history.stream().mapToInt(m -> ContextItem.estimateTokens(m.getContent())).sum();
	}
}
