package com.abhiraj.archyra.ai.chat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Package-private: the rest of the application goes through the chat services. Messages in PENDING are invisible to every read here but {@link #findPending}. */
interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

	/** What a conversation shows: user messages that were answered, and assistant messages. Oldest first, a question before its answer. */
	@Query("select m from ChatMessage m where m.conversationId = :conversationId and (m.status is null or m.status = com.abhiraj.archyra.ai.chat.MessageStatus.ANSWERED) "
			+ "order by m.createdAt asc, m.role desc")
	Page<ChatMessage> findVisible(@Param("conversationId") UUID conversationId, Pageable pageable);

	/** The newest visible messages, newest first. */
	@Query("select m from ChatMessage m where m.conversationId = :conversationId and (m.status is null or m.status = com.abhiraj.archyra.ai.chat.MessageStatus.ANSWERED) "
			+ "order by m.createdAt desc, m.role asc")
	List<ChatMessage> findNewestVisible(@Param("conversationId") UUID conversationId, Pageable pageable);

	@Query("select count(m) from ChatMessage m where m.conversationId = :conversationId and (m.status is null or m.status = com.abhiraj.archyra.ai.chat.MessageStatus.ANSWERED)")
	long countVisible(@Param("conversationId") UUID conversationId);

	@Query("select m.conversationId as conversationId, count(m) as total from ChatMessage m where m.conversationId in :ids "
			+ "and (m.status is null or m.status = com.abhiraj.archyra.ai.chat.MessageStatus.ANSWERED) group by m.conversationId")
	List<MessageCount> countVisibleByConversation(@Param("ids") Collection<UUID> ids);

	interface MessageCount {
		UUID getConversationId();

		long getTotal();
	}

	@Query("select m from ChatMessage m where m.role = com.abhiraj.archyra.ai.chat.MessageRole.USER and m.status = com.abhiraj.archyra.ai.chat.MessageStatus.PENDING")
	List<ChatMessage> findPending();

	@Query("select m from ChatMessage m where m.conversationId in :ids and m.status = com.abhiraj.archyra.ai.chat.MessageStatus.PENDING")
	List<ChatMessage> findPendingIn(@Param("ids") Collection<UUID> conversationIds);

	/**
	 * The guard that makes a refund happen once, and only for a question that was never answered: flips
	 * {@code quota_refunded} only for a charged, not yet refunded message that is still PENDING. 0 rows means already
	 * refunded, never charged, answered, or gone.
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update ChatMessage m set m.quotaRefunded = true where m.id = :id and m.quotaCharged = true and m.quotaRefunded = false "
			+ "and m.status = com.abhiraj.archyra.ai.chat.MessageStatus.PENDING")
	int markQuotaRefunded(@Param("id") UUID id);

	/** PENDING to ANSWERED; 0 rows if the message is gone or was already settled. */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update ChatMessage m set m.status = com.abhiraj.archyra.ai.chat.MessageStatus.ANSWERED where m.id = :id and m.status = com.abhiraj.archyra.ai.chat.MessageStatus.PENDING")
	int markAnswered(@Param("id") UUID id);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from ChatMessage m where m.id = :id and m.status = com.abhiraj.archyra.ai.chat.MessageStatus.PENDING")
	int deletePending(@Param("id") UUID id);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from ChatMessage m where m.conversationId = :conversationId")
	int deleteByConversationId(@Param("conversationId") UUID conversationId);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from ChatMessage m where m.conversationId in :ids")
	int deleteByConversationIdIn(@Param("ids") Collection<UUID> ids);

	@Query("select count(m) from ChatMessage m where m.conversationId = :conversationId")
	long countAll(@Param("conversationId") UUID conversationId);
}
