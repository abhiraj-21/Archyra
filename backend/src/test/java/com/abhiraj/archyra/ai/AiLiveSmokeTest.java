package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.ai.llm.LiveProbe;
import com.abhiraj.archyra.ai.llm.ProviderGateway;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/**
 * Real questions against the real providers. Opt-in, never part of the default suite: it needs
 * {@code ARCHYRA_AI_LIVE_TESTS=true} and the key of the provider under test (environment or {@code .env}; a provider without
 * its key is skipped silently), and every case spends a request of that provider's free tier.
 *
 * <pre>ARCHYRA_AI_LIVE_TESTS=true ./mvnw test -Dtest=AiLiveSmokeTest</pre>
 *
 * It checks what the fake servers cannot: that the request formats, model ids and base URLs of the configuration are accepted
 * by each real service (one trivial question per provider, asked through the real gateway on a chain of just that provider),
 * and that a real answer to a real question survives grounding.
 */
@EnabledIfEnvironmentVariable(named = "ARCHYRA_AI_LIVE_TESTS", matches = "true")
class AiLiveSmokeTest extends AiIntegrationSupport {

	@Autowired
	AiProperties properties;
	@Autowired
	Environment environment;
	@Autowired
	ProviderGateway gateway;

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "gemini", "groq", "openrouter", "cloudflare", "zai", "cohere", "ovh", "llm7", "nvidia-nim", "kilo", "gonka" })
	void eachProviderAnswersATrivialQuestion(String id) {
		// ovh needs no key: it is only asked when it is switched on (OVH_ENABLED=true), as are the opt-in providers (<ID>_ENABLED=true)
		var answer = LiveProbe.ping(environment, properties, id);
		assumeTrue(answer.isPresent(), id + " is not in the chain (no key, or switched off)");
		System.out.println("LIVE SMOKE TEST: " + id + " answered with " + answer.get().text().length() + " characters");
		assertThat(answer.get().provider()).isEqualTo(id);
		assertThat(answer.get().text()).isNotBlank().doesNotContain("<think>");
	}

	@Test
	void aRealQuestionGetsARealGroundedAnswer() throws Exception {
		assumeTrue(gateway.anyUsable(), "no provider is in the chain (no key is set)");
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");

		MvcResult result = mockMvc.perform(post("/api/v1/analyses/" + id + "/chat").header("Authorization", bearer(owner))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"What does the place method of DefaultOrderService do, and which repository does it use?\"}")).andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
		String answer = JsonPath.read(body, "$.answer");
		String provider = JsonPath.read(body, "$.provider");
		List<Map<String, Object>> sources = JsonPath.read(body, "$.sources");
		System.out.println("LIVE SMOKE TEST: provider=" + provider + ", answer of " + answer.length() + " characters, " + sources.size() + " verified sources");
		assertThat(answer).isNotBlank();
		assertThat(provider).isNotBlank();
		assertThat((String) JsonPath.read(body, "$.providerName")).isNotBlank();
		assertThat(sources).as("a real answer to a question about named code cites at least one verified source").isNotEmpty();
		assertThat(sources).allSatisfy(source -> assertThat((String) source.get("id")).matches("(class|interface|method|function):.+"));
		assertThat(answer).doesNotContain("<repository_context>").doesNotContain("\u3010");
	}
}
