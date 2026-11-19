package com.abhiraj.archyra.ai.chat;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** {@code POST /api/v1/analyses/{analysisId}/chat}: thin; everything is in {@link ChatService}. */
@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/chat")
@RequiredArgsConstructor
class ChatController {

	private final ChatService chatService;

	@PostMapping
	ChatAnswer ask(@PathVariable String analysisId, @RequestBody ChatQuestion question) {
		return chatService.ask(analysisId, question);
	}
}
