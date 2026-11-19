package com.abhiraj.archyra.ai;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.chat.ConversationService;
import com.abhiraj.archyra.ai.index.AiIndexService;

import lombok.RequiredArgsConstructor;

/** Removes everything Ask AI stores for an analysis: conversations and messages, the index and the chunks. Idempotent. */
@Component
@RequiredArgsConstructor
class AiDataPurger {

	private final AiIndexService index;
	private final ConversationService conversations;

	/** Conversations first: a question still being answered gets its quota unit back before its message is removed. */
	void purge(UUID analysisId) {
		conversations.purge(analysisId);
		index.purge(analysisId);
	}
}
