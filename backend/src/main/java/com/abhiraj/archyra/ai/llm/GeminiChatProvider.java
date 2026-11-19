package com.abhiraj.archyra.ai.llm;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.llm.ProviderException.Kind;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;

import lombok.extern.slf4j.Slf4j;

/**
 * Gemini (Google AI Studio), the primary provider, through Spring AI's Google GenAI model built here explicitly (no
 * auto-configuration, so no default {@code ChatModel} exists for other code to pick up). The base URL, model and key come
 * from {@code archyra.ai.gemini.*}; there are no retries at any layer, because the gateway decides about the fallback.
 */
@Slf4j
@Component
class GeminiChatProvider implements ChatProvider {

	private static final double TEMPERATURE = 0.2;

	private final AiProperties properties;
	private volatile GoogleGenAiChatModel model;

	GeminiChatProvider(AiProperties properties) {
		this.properties = properties;
	}

	@Override
	public ProviderId id() {
		return ProviderId.GEMINI;
	}

	@Override
	public boolean configured() {
		return properties.gemini().configured();
	}

	@Override
	public int contextBudgetTokens() {
		return properties.context().geminiMaxTokens();
	}

	@Override
	public void warmUp() {
		if (configured()) {
			model();
		}
	}

	@Override
	public LlmAnswer chat(LlmRequest request) {
		try {
			ChatResponse response = model().call(new Prompt(messages(request)));
			String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
					? null : response.getResult().getOutput().getText();
			if (text == null || text.isBlank()) {
				throw new ProviderException(ProviderId.GEMINI, Kind.EMPTY, null, null);
			}
			return new LlmAnswer(text, ProviderId.GEMINI);
		}
		catch (ProviderException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw ProviderErrors.classify(ProviderId.GEMINI, ex);
		}
	}

	static List<Message> messages(LlmRequest request) {
		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(request.system()));
		for (ChatTurn turn : request.history()) {
			messages.add(turn.role() == ChatTurn.Role.USER ? new UserMessage(turn.content()) : new AssistantMessage(turn.content()));
		}
		messages.add(new UserMessage(request.userMessage()));
		return messages;
	}

	private GoogleGenAiChatModel model() {
		GoogleGenAiChatModel built = model;
		if (built == null) {
			synchronized (this) {
				built = model;
				if (built == null) {
					built = build();
					model = built;
				}
			}
		}
		return built;
	}

	private GoogleGenAiChatModel build() {
		AiProperties.Gemini gemini = properties.gemini();
		HttpOptions http = HttpOptions.builder()
				.baseUrl(gemini.baseUrl())
				.timeout((int) properties.timeout().read().toMillis())
				.retryOptions(HttpRetryOptions.builder().attempts(1).build()) // one attempt: the gateway owns fallback
				.build();
		Client client = Client.builder().apiKey(gemini.apiKey()).httpOptions(http).build();
		GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
				.model(gemini.model())
				.temperature(TEMPERATURE)
				.maxOutputTokens(properties.answer().maxOutputTokens())
				.build();
		return GoogleGenAiChatModel.builder().genAiClient(client).options(options)
				.retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
	}
}
