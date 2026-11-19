package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.ai.llm.ProviderCooldowns;
import com.abhiraj.archyra.ai.llm.ProviderId;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;

/**
 * Chat tests: {@link AiIntegrationSupport} plus the fake Gemini and Groq servers, with both keys set. A subclass overrides
 * what it needs with its own {@code @TestPropertySource}. Cool-downs are a few seconds here, and every test waits for them
 * to end first, so one test's 429 cannot refuse the next test's question.
 */
@TestPropertySource(properties = {
		"archyra.ai.gemini.api-key=test-gemini-key", "archyra.ai.groq.api-key=test-groq-key",
		"archyra.ai.provider.cooldown-default=2s", "archyra.ai.provider.cooldown-max=3s" })
public abstract class ChatIntegrationSupport extends AiIntegrationSupport {

	protected static final String PLACE = "method:com.example.shop.service.DefaultOrderService#place(OrderRequest)";
	protected static final String PLACE_INTERFACE = "method:com.example.shop.service.OrderService#place(OrderRequest)";
	protected static final String PLACE_QUESTION = "What does `place` do?";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	protected Neo4jClient neo4j;
	@Autowired
	protected ProviderCooldowns cooldowns;

	@DynamicPropertySource
	static void fakeProviders(DynamicPropertyRegistry registry) {
		registry.add("archyra.ai.gemini.base-url", FakeProviders.GEMINI::baseUrl);
		registry.add("archyra.ai.groq.base-url", FakeProviders.GROQ::baseUrl);
	}

	@BeforeEach
	void freshFakeProviders() {
		FakeProviders.GEMINI.reset();
		FakeProviders.GROQ.reset();
		await().atMost(Duration.ofSeconds(10)).until(
				() -> !cooldowns.isCoolingDown(ProviderId.GEMINI) && !cooldowns.isCoolingDown(ProviderId.GROQ));
	}

	protected static String chatPath(UUID analysisId) {
		return "/api/v1/analyses/" + analysisId + "/chat";
	}

	protected MvcResult chat(User user, UUID analysisId, String message) throws Exception {
		return chat(user, analysisId, message, null);
	}

	protected MvcResult chat(User user, UUID analysisId, String message, String conversationId) throws Exception {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("message", message);
		if (conversationId != null) {
			body.put("conversationId", conversationId);
		}
		return mockMvc.perform(post(chatPath(analysisId)).header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))).andReturn();
	}

	/** A question that is answered: asserts the 200 and returns the body. */
	protected String ask(User user, UUID analysisId, String message, String conversationId) throws Exception {
		MvcResult result = chat(user, analysisId, message, conversationId);
		assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
		return result.getResponse().getContentAsString();
	}

	protected static <T> T read(String json, String path) {
		return JsonPath.read(json, path);
	}

	protected static String code(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
	}

	/** A user with a completed, READY analysis of the sample shop. */
	protected record Ready(User user, UUID analysisId) {
	}

	protected Ready readyShop() throws Exception {
		return readyShop(newUser());
	}

	protected Ready readyShop(User user) throws Exception {
		UUID id = analyzeCompleted(user, SHOP);
		awaitIndexStatus(user, id, "READY");
		return new Ready(user, id);
	}

	protected int used(User user, String usageType) throws Exception {
		MvcResult usage = getAs(user, "/api/v1/usage");
		List<Integer> used = JsonPath.read(usage.getResponse().getContentAsString(), "$.usage[?(@.usageType=='" + usageType + "')].used");
		return used.getFirst();
	}

	protected int storedMessages(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM chat_messages m JOIN conversations c ON c.id = m.conversation_id WHERE c.analysis_id = ?",
				Integer.class, analysisId);
	}

	protected int conversationRows(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM conversations WHERE analysis_id = ?", Integer.class, analysisId);
	}

	/** Deletes a node of the analysis from Neo4j, as if the graph had changed under a question. */
	protected void removeNode(UUID analysisId, String nodeId) {
		neo4j.query("MATCH (n {analysisId: $analysisId, nodeId: $nodeId}) DETACH DELETE n")
				.bind(analysisId.toString()).to("analysisId").bind(nodeId).to("nodeId").run();
	}
}
