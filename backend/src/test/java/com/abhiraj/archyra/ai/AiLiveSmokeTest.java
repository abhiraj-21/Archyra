package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/**
 * One real question against the real providers. Opt-in, never part of the default suite: it needs
 * {@code ARCHYRA_AI_LIVE_TESTS=true} and at least one of {@code GEMINI_API_KEY} / {@code GROQ_API_KEY} (environment or
 * {@code .env}), and it spends one question of that key's free tier.
 *
 * <pre>ARCHYRA_AI_LIVE_TESTS=true ./mvnw test -Dtest=AiLiveSmokeTest</pre>
 *
 * It checks what the fake servers cannot: that the request formats, model ids and base URLs of the configuration are accepted
 * by the real services, and that a real answer survives grounding.
 */
@EnabledIfEnvironmentVariable(named = "ARCHYRA_AI_LIVE_TESTS", matches = "true")
class AiLiveSmokeTest extends AiIntegrationSupport {

	@Autowired
	AiProperties properties;

	@Test
	void aRealQuestionGetsARealGroundedAnswer() throws Exception {
		assumeTrue(properties.gemini().configured() || properties.groq().configured(), "no provider key is set");
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
		assertThat(provider).isIn("GEMINI", "GROQ");
		assertThat(sources).allSatisfy(source -> assertThat((String) source.get("id")).matches("(class|interface|method|function):.+"));
		assertThat(answer).doesNotContain("<repository_context>");
	}
}
