package com.abhiraj.archyra.ai.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.abhiraj.archyra.ai.AiProperties;

/** The startup check is switched by {@code archyra.ai.embedding.verify-on-startup} and stops startup on a bad model. */
class EmbeddingStartupCheckTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AiProperties.class)
	static class Config {
		@Bean
		EmbeddingService embeddingService(AiProperties properties) {
			return new EmbeddingService(properties);
		}

		@Bean
		@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "archyra.ai.embedding.verify-on-startup", havingValue = "true")
		EmbeddingStartupCheck check(EmbeddingService embeddings) {
			return new EmbeddingStartupCheck(embeddings);
		}
	}

	private ApplicationContextRunner runner(String modelUri, boolean verify) {
		return new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues(
				"archyra.ai.gemini.model=m", "archyra.ai.gemini.base-url=http://localhost:1",
				"archyra.ai.groq.model=m", "archyra.ai.groq.base-url=http://localhost:2",
				"archyra.ai.embedding.model-uri=" + modelUri,
				"archyra.ai.embedding.tokenizer-uri=" + EmbeddingServiceTest.TOKENIZER_URI,
				"archyra.ai.embedding.verify-on-startup=" + verify);
	}

	@Test
	void withTheRealModelTheCheckPasses() {
		runner(EmbeddingServiceTest.MODEL_URI, true).run(context -> {
			assertThat(context).hasSingleBean(EmbeddingStartupCheck.class);
			context.getBean(EmbeddingStartupCheck.class).run(null); // what SpringApplication does at startup
		});
	}

	@Test
	void aMissingModelFailsTheCheckWithAHelpfulMessage() {
		runner("file:/definitely/not/here/model.onnx", true).run(context -> {
			EmbeddingStartupCheck check = context.getBean(EmbeddingStartupCheck.class);
			assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("fetch-embedding-model.sh");
		});
	}

	@Test
	void theCheckExistsOnlyWhenSwitchedOn() {
		runner("file:/definitely/not/here/model.onnx", false)
				.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EmbeddingStartupCheck.class));
	}
}
