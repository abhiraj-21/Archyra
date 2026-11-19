package com.abhiraj.archyra.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

/** {@code GET /api/v1/admin/ai/providers}: ADMIN only, the real state of the chain, and never a secret. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = { "archyra.ai.providers.gemini.api-key=SECRETGEMINIKEY1234", "archyra.ai.providers.gemini.base-url=http://localhost:1",
		"archyra.ai.providers.groq.api-key=SECRETGROQKEY5678", "archyra.ai.providers.groq.base-url=http://localhost:1",
		"archyra.ai.providers.groq.requests-per-minute=1", "archyra.ai.providers.openrouter.api-key=SECRETROUTERKEY9012",
		"archyra.ai.providers.openrouter.base-url=http://localhost:1", "archyra.ai.providers.nvidia-nim.api-key=SECRETNVIDIAKEY3456" })
class AdminAiProvidersIntegrationTest {

	private static final String PATH = "/api/v1/admin/ai/providers";
	private static final String PASSWORD = "correct horse battery";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JwtService jwtService;
	@Autowired
	ProviderRegistry registry;
	@Autowired
	ProviderCooldowns cooldowns;

	private User user() {
		User user = userService.registerIfAbsent("prov-" + UUID.randomUUID() + "@example.com", "Plain User", passwordEncoder.encode(PASSWORD),
				Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	private User admin() {
		return userService.save(User.administrator("prov-" + UUID.randomUUID() + "@example.com", "An Admin", passwordEncoder.encode(PASSWORD),
				Instant.now()));
	}

	private String bearer(User user) {
		return "Bearer " + jwtService.issueFor(user).value();
	}

	private MvcResult getAs(User caller) throws Exception {
		return mockMvc.perform(get(PATH).header("Authorization", bearer(caller))).andReturn();
	}

	@Test
	void onlyAnAdministratorMayRead() throws Exception {
		assertThat(getAs(user()).getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get(PATH)).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(get(PATH).header("Authorization", "Bearer tampered")).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(getAs(admin()).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void anAdminTokenOfAnAccountDisabledSinceIsRefused() throws Exception {
		User first = admin();
		User second = admin();
		String staleToken = bearer(second);
		second.setEnabled(false);
		userService.save(second);

		assertThat(mockMvc.perform(get(PATH).header("Authorization", staleToken)).andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(getAs(first).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void everyKnownProviderIsListedWithItsStateAndNeverASecret() throws Exception {
		MvcResult result = getAs(admin());

		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("SECRET").doesNotContain("KEY1234").doesNotContain("KEY5678").doesNotContain("localhost")
				.doesNotContain("apiKey").doesNotContain("api-key").doesNotContain("baseUrl");
		List<String> ids = JsonPath.read(body, "$[*].id");
		assertThat(ids).containsExactly("gemini", "groq", "openrouter", "cloudflare", "zai", "cohere", "ovh", "llm7", "nvidia-nim", "kilo", "gonka");
		assertThat(first(body, "gemini")).containsEntry("displayName", "Gemini").containsEntry("configured", true).containsEntry("enabled", true)
				.containsEntry("state", "AVAILABLE").containsEntry("requestsToday", 0).containsEntry("requestsThisMonth", 0)
				.doesNotContainKey("coolingUntil");
		assertThat(first(body, "cohere")).containsEntry("configured", false).containsEntry("state", "DISABLED"); // no key: not in the chain
		assertThat(first(body, "ovh")).containsEntry("state", "DISABLED").containsEntry("enabled", false); // switched off in the test profile
		// a key alone does not enable an opt-in provider
		assertThat(first(body, "nvidia-nim")).containsEntry("configured", true).containsEntry("enabled", false).containsEntry("state", "DISABLED");
	}

	@Test
	void aCoolingProviderAndALocallyLimitedOneShowTheirState() throws Exception {
		cooldowns.rateLimited("openrouter", Duration.ofMinutes(10));
		var groq = registry.all().stream().filter(slot -> slot.settings().id().equals("groq")).findFirst().orElseThrow();
		assertThat(groq.guard().tryAcquire().granted()).isTrue(); // groq allows one request a minute: now it is used up
		try {
			String body = getAs(admin()).getResponse().getContentAsString();

			Map<String, Object> openRouter = first(body, "openrouter");
			assertThat(openRouter).containsEntry("state", "COOLING_DOWN");
			assertThat(Instant.parse((String) openRouter.get("coolingUntil"))).isAfter(Instant.now().plus(Duration.ofMinutes(5)));
			Map<String, Object> limited = first(body, "groq");
			assertThat(limited).containsEntry("state", "RATE_LIMITED_LOCALLY").containsEntry("requestsToday", 1).containsEntry("requestsThisMonth", 1);
		}
		finally {
			cooldowns.succeeded("openrouter");
		}
	}

	private static Map<String, Object> first(String body, String id) {
		List<Map<String, Object>> found = JsonPath.read(body, "$[?(@.id=='" + id + "')]");
		assertThat(found).hasSize(1);
		return found.getFirst();
	}
}
