package com.abhiraj.archyra.ai.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** The conversation endpoints; thin, see {@link ConversationService}. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class ConversationController {

	private final ConversationService conversations;

	@GetMapping("/analyses/{analysisId}/conversations")
	PageOf<ConversationSummary> list(@PathVariable String analysisId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return conversations.list(analysisId, page, size);
	}

	@PostMapping("/analyses/{analysisId}/conversations")
	@ResponseStatus(HttpStatus.CREATED)
	ConversationSummary create(@PathVariable String analysisId) {
		return conversations.create(analysisId);
	}

	@DeleteMapping("/conversations/{conversationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable String conversationId) {
		conversations.delete(conversationId);
	}

	@GetMapping("/conversations/{conversationId}/messages")
	PageOf<MessageResponse> messages(@PathVariable String conversationId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return conversations.messages(conversationId, page, size);
	}
}
