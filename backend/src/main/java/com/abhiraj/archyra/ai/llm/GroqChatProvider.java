package com.abhiraj.archyra.ai.llm;

import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.llm.ProviderException.Kind;

import lombok.extern.slf4j.Slf4j;

/**
 * Groq (free plan), the fallback, through Spring AI's OpenAI-compatible model pointed at Groq's base URL. Built here
 * explicitly; the SDK's own retries are off ({@code maxRetries = 0}). Its 429 carries {@code Retry-After}, which sets the
 * cool-down.
 */
@Slf4j
@Component
class GroqChatProvider implements ChatProvider {

	private static final double TEMPERATURE = 0.2;

	private final AiProperties properties;
	private volatile OpenAiChatModel model;

	GroqChatProvider(AiProperties properties) {
		this.properties = properties;
	}

	@Override
	public ProviderId id() {
		return ProviderId.GROQ;
	}

	@Override
	public boolean configured() {
		return properties.groq().configured();
	}

	@Override
	public int contextBudgetTokens() {
		return properties.context().groqMaxTokens();
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
			List<Message> messages = GeminiChatProvider.messages(request);
			ChatResponse response = model().call(new Prompt(messages));
			String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
					? null : response.getResult().getOutput().getText();
			if (text == null || text.isBlank()) {
				throw new ProviderException(ProviderId.GROQ, Kind.EMPTY, null, null);
			}
			return new LlmAnswer(text, ProviderId.GROQ);
		}
		catch (ProviderException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw ProviderErrors.classify(ProviderId.GROQ, ex);
		}
	}

	private OpenAiChatModel model() {
		OpenAiChatModel built = model;
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

	private OpenAiChatModel build() {
		AiProperties.Groq groq = properties.groq();
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
				.baseUrl(groq.baseUrl())
				.apiKey(groq.apiKey())
				.model(groq.model())
				.temperature(TEMPERATURE)
				.maxCompletionTokens(properties.answer().maxOutputTokens())
				.timeout(properties.timeout().read())
				.maxRetries(0)
				.replayReasoningContent(false); // Groq rejects the replay property
		if (groq.reasoningEffort() != null && !groq.reasoningEffort().isBlank()) {
			options.reasoningEffort(groq.reasoningEffort().trim());
		}
		return OpenAiChatModel.builder().options(options.build()).build();
	}
}
