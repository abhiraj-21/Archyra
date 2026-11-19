package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AiPropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AiProperties.class)
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
			.withPropertyValues(
					"archyra.ai.gemini.model=test-gemini", "archyra.ai.gemini.base-url=http://localhost:1",
					"archyra.ai.groq.model=test-groq", "archyra.ai.groq.base-url=http://localhost:2",
					"archyra.ai.embedding.model-uri=file:m.onnx", "archyra.ai.embedding.tokenizer-uri=file:t.json");

	@Test
	void bindsEverythingAndKeepsTheDocumentedDefaults() {
		runner.withPropertyValues("archyra.ai.gemini.api-key=secret-gemini-key", "archyra.ai.context.groq-max-tokens=2000")
				.run(context -> {
					AiProperties ai = context.getBean(AiProperties.class);
					assertThat(ai.gemini().model()).isEqualTo("test-gemini");
					assertThat(ai.groq().baseUrl()).isEqualTo("http://localhost:2");
					assertThat(ai.context().groqMaxTokens()).isEqualTo(2000);
					assertThat(ai.context().geminiMaxTokens()).isEqualTo(12000);
					assertThat(ai.timeout().total()).isEqualTo(Duration.ofSeconds(60));
					assertThat(ai.retrieval().vectorTopK()).isEqualTo(12);
					assertThat(ai.limits().messagesPerConversation()).isEqualTo(100);
					assertThat(ai.embedding().batchSize()).isEqualTo(32);
				});
	}

	@Test
	void aProviderIsConfiguredOnlyWithANonBlankKey() {
		runner.withPropertyValues("archyra.ai.gemini.api-key=k", "archyra.ai.groq.api-key=   ").run(context -> {
			AiProperties ai = context.getBean(AiProperties.class);
			assertThat(ai.gemini().configured()).isTrue();
			assertThat(ai.groq().configured()).isFalse();
		});
	}

	@Test
	void startupSucceedsWithoutAnyKey() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			AiProperties ai = context.getBean(AiProperties.class);
			assertThat(ai.gemini().configured()).isFalse();
			assertThat(ai.groq().configured()).isFalse();
		});
	}

	@Test
	void keysNeverAppearInToString() {
		runner.withPropertyValues("archyra.ai.gemini.api-key=secret-gemini-key", "archyra.ai.groq.api-key=secret-groq-key")
				.run(context -> {
					String text = context.getBean(AiProperties.class).toString();
					assertThat(text).doesNotContain("secret-gemini-key").doesNotContain("secret-groq-key");
				});
	}

	@Test
	void anInvalidValueFailsStartup() {
		runner.withPropertyValues("archyra.ai.chunk.max-per-analysis=0").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("archyra.ai.retrieval.min-similarity=3").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("archyra.ai.limits.messages-per-conversation=1").run(context -> assertThat(context).hasFailed());
	}
}
