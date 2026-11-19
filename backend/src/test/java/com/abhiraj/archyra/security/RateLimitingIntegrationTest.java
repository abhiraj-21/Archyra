package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.analysis.AnalysisPipeline;
import com.abhiraj.archyra.analysis.AnalysisRepository;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.user.User;
import com.jayway.jsonpath.JsonPath;

/** The throttles through the real chain: login 3/min, register + forgot + resend 2/hour, analyses 2/hour per user. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"app.security.rate-limit.login-per-ip-per-minute=3",
		"app.security.rate-limit.sensitive-per-ip-per-hour=2",
		"app.security.rate-limit.create-analysis-per-user-per-hour=2" })
class RateLimitingIntegrationTest {

	private static final String LOGIN_BODY = "{\"email\":\"nobody@example.com\",\"password\":\"not the password\"}";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JwtService jwtService;
	@Autowired
	AnalysisRepository analyses;
	@MockitoBean
	AnalysisPipeline pipeline;

	private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
		return request.with(r -> {
			r.setRemoteAddr(ip);
			return r;
		});
	}

	private MvcResult login(String ip) throws Exception {
		return mockMvc.perform(from(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)).andReturn();
	}

	private MvcResult postJson(String path, String body, String ip) throws Exception {
		return mockMvc.perform(from(post(path), ip).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	// ---- login ----------------------------------------------------------------------------------------

	@Test
	void loginIsLimitedPerIpWith429AndRetryAfter() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertThat(login("203.0.113.10").getResponse().getStatus()).isEqualTo(401);
		}

		MvcResult limited = login("203.0.113.10");

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(JsonPath.<String>read(limited.getResponse().getContentAsString(), "$.code")).isEqualTo("RATE_LIMITED");
		assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
		assertThat(limited.getResponse().getCookies()).isEmpty();
		assertThat(login("203.0.113.11").getResponse().getStatus()).isEqualTo(401); // someone else is unaffected
	}

	@Test
	void deletingAnAccountWithAPasswordOrAnEmailedTokenSharesTheLoginBucket() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertThat(postJson("/api/v1/users/me/delete", "{\"password\":\"x\"}", "203.0.113.30").getResponse().getStatus()).isEqualTo(401);
		}

		MvcResult limited = postJson("/api/v1/users/me/delete-confirm", "{\"token\":\"x\"}", "203.0.113.30");

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(JsonPath.<String>read(limited.getResponse().getContentAsString(), "$.code")).isEqualTo("RATE_LIMITED");
		assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(postJson("/api/v1/users/me/delete", "{\"password\":\"x\"}", "203.0.113.31").getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void theDeletionEmailIsLimitedLikeTheOtherEmailRoutes() throws Exception {
		for (int i = 0; i < 2; i++) {
			assertThat(postJson("/api/v1/users/me/delete-request", "", "203.0.113.32").getResponse().getStatus()).isEqualTo(401);
		}

		assertThat(postJson("/api/v1/users/me/delete-request", "", "203.0.113.32").getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	void aRefusedLoginDoesNotReachTheAuthenticationCode() throws Exception {
		for (int i = 0; i < 3; i++) {
			login("203.0.113.12");
		}

		// the body is not even read: malformed JSON still gets the 429, not a 400
		MvcResult limited = mockMvc.perform(from(post("/api/v1/auth/login"), "203.0.113.12")
				.contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn();

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
	}

	// ---- email-sending endpoints ----------------------------------------------------------------------

	@Test
	void registerForgotPasswordAndResendShareOneBucketPerIp() throws Exception {
		String ip = "203.0.113.20";
		String register = "{\"email\":\"rl-" + UUID.randomUUID() + "@example.com\",\"password\":\"correct horse battery\",\"name\":\"RL\"}";

		assertThat(postJson("/api/v1/auth/register", register, ip).getResponse().getStatus()).isEqualTo(202);
		assertThat(postJson("/api/v1/auth/forgot-password", "{\"email\":\"a@example.com\"}", ip).getResponse().getStatus()).isEqualTo(202);

		MvcResult limited = postJson("/api/v1/auth/resend-verification", "{\"email\":\"a@example.com\"}", ip);

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 3600L);
		assertThat(postJson("/api/v1/auth/forgot-password", "{\"email\":\"a@example.com\"}", "203.0.113.21").getResponse().getStatus())
				.isEqualTo(202);
	}

	@Test
	void otherEndpointsAreNotThrottledByTheFilter() throws Exception {
		String ip = "203.0.113.30";

		for (int i = 0; i < 8; i++) {
			assertThat(mockMvc.perform(from(get("/api/v1/auth/csrf"), ip)).andReturn().getResponse().getStatus()).isEqualTo(200);
			assertThat(postJson("/api/v1/auth/verify-email", "{\"token\":\"nope\"}", ip).getResponse().getStatus()).isEqualTo(400);
		}
	}

	// ---- who counts as the client -----------------------------------------------------------------------------

	@Test
	void aClientCannotDodgeTheLimitByForgingForwardedFor() throws Exception {
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(from(post("/api/v1/auth/login"), "203.0.113.50").header("X-Forwarded-For", "6.6.6." + i)
					.contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY));
		}

		MvcResult forged = mockMvc.perform(from(post("/api/v1/auth/login"), "203.0.113.50").header("X-Forwarded-For", "6.6.6.99")
				.contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)).andReturn();

		assertThat(forged.getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	void theRefusalCarriesCorsHeadersSoTheBrowserCanReadIt() throws Exception {
		for (int i = 0; i < 3; i++) {
			login("203.0.113.60");
		}

		MvcResult limited = mockMvc.perform(from(post("/api/v1/auth/login"), "203.0.113.60").header("Origin", "http://localhost:5173")
				.contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)).andReturn();

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(limited.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
		assertThat(limited.getResponse().getHeader("Access-Control-Expose-Headers")).contains("Retry-After");
	}

	// ---- analyses per user ------------------------------------------------------------------------------------

	private String tokenOf(UUID userId) {
		User user = User.registered("rl@example.com", "RL", null, Instant.now());
		ReflectionTestUtils.setField(user, "id", userId);
		return "Bearer " + jwtService.issueFor(user).value();
	}

	private MvcResult startAnalysis(UUID userId) throws Exception {
		// a new repository each time: asking again for one that is still running would join it instead of creating another
		return mockMvc.perform(post("/api/v1/analyses").header("Authorization", tokenOf(userId))
				.contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"https://github.com/owner/repo-" + UUID.randomUUID() + "\"}")).andReturn();
	}

	@Test
	void startingAnalysesIsLimitedPerUserAndARefusalCreatesNothing() throws Exception {
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();

		assertThat(startAnalysis(alice).getResponse().getStatus()).isEqualTo(202);
		assertThat(startAnalysis(alice).getResponse().getStatus()).isEqualTo(202);
		MvcResult limited = startAnalysis(alice);

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(JsonPath.<String>read(limited.getResponse().getContentAsString(), "$.code")).isEqualTo("RATE_LIMITED");
		assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 3600L);
		assertThat(analyses.findByOwnerId(alice, org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(2);
		assertThat(startAnalysis(bob).getResponse().getStatus()).isEqualTo(202); // the limit is per user, not shared
		assertThat(mockMvc.perform(get("/api/v1/analyses").header("Authorization", tokenOf(alice))).andReturn().getResponse().getStatus())
				.isEqualTo(200); // reading is not limited
	}
}
