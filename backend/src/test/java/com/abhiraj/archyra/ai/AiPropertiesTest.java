package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.abhiraj.archyra.ai.llm.ProviderConfig;

class AiPropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AiProperties.class)
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
			.withPropertyValues("archyra.ai.embedding.model-uri=file:m.onnx", "archyra.ai.embedding.tokenizer-uri=file:t.json");

	@Test
	void bindsEverythingAndKeepsTheDocumentedDefaults() {
		runner.withPropertyValues("archyra.ai.providers.order=groq, openrouter", "archyra.ai.providers.max-attempts=2").run(context -> {
			AiProperties ai = context.getBean(AiProperties.class);
			assertThat(ai.providers().order()).containsExactly("groq", "openrouter");
			assertThat(ai.providers().maxAttempts()).isEqualTo(2);
			assertThat(ai.providers().cooldownDefault()).isEqualTo(Duration.ofSeconds(30));
			assertThat(ai.providers().cooldownMax()).isEqualTo(Duration.ofHours(1));
			assertThat(ai.providers().cooldownMisconfigured()).isEqualTo(Duration.ofMinutes(30));
			assertThat(ai.timeout().total()).isEqualTo(Duration.ofSeconds(60));
			assertThat(ai.retrieval().vectorTopK()).isEqualTo(12);
			assertThat(ai.limits().messagesPerConversation()).isEqualTo(100);
			assertThat(ai.embedding().batchSize()).isEqualTo(32);
		});
	}

	@Test
	void maxAttemptsDefaultsToFourAndTheOrderComesFromConfiguration() {
		runner.run(context -> {
			AiProperties ai = context.getBean(AiProperties.class);
			assertThat(ai.providers().maxAttempts()).isEqualTo(4);
			assertThat(ai.providers().order()).isEmpty(); // the order itself comes from application.properties
		});
	}

	@Test
	void startupSucceedsWithoutAnyKey() {
		runner.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void keysNeverAppearInToString() {
		ProviderConfig config = new ProviderConfig(null, true, "Groq", "http://x", "secret-groq-key", null, "m", null, null, null, null, null,
				null, null, null);
		assertThat(config.toString()).doesNotContain("secret-groq-key").contains("***");
		runner.withPropertyValues("archyra.ai.providers.groq.api-key=secret-groq-key").run(context ->
				assertThat(context.getBean(AiProperties.class).toString()).doesNotContain("secret-groq-key"));
	}

	@Test
	void anInvalidValueFailsStartup() {
		runner.withPropertyValues("archyra.ai.chunk.max-per-analysis=0").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("archyra.ai.retrieval.min-similarity=3").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("archyra.ai.limits.messages-per-conversation=1").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("archyra.ai.providers.max-attempts=0").run(context -> assertThat(context).hasFailed());
	}
}
