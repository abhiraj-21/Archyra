package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authorization rules of the stateless chain, against a probe controller so they hold for any endpoint
 * that sits under each path prefix.
 */
@WebMvcTest(SecurityRulesTest.Probe.class)
@Import({ SecuritySliceConfig.class, SecurityRulesTest.Probe.class }) // nested test classes are not component-scanned
class SecurityRulesTest {

	@RestController
	static class Probe {
		@GetMapping("/api/v1/probe")
		String user() {
			return "ok";
		}

		@PostMapping("/api/v1/probe")
		String post() {
			return "ok";
		}

		@GetMapping("/api/v1/admin/probe")
		String admin() {
			return "ok";
		}

		@GetMapping("/api/v1/auth/open")
		String open() {
			return "ok";
		}

		@GetMapping("/api/v1/auth/me")
		String me() {
			return "ok";
		}

		@GetMapping("/elsewhere")
		String elsewhere() {
			return "ok";
		}
	}

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JwtService jwtService;
	@Autowired
	JwtEncoder jwtEncoder;
	@Autowired
	SecurityProperties properties;

	private String userToken() {
		return jwtService.issue(UUID.randomUUID(), List.of("USER")).value();
	}

	private String adminToken() {
		return jwtService.issue(UUID.randomUUID(), List.of("USER", "ADMIN")).value();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@Test
	void noTokenIs401WithTheStandardBodyAndABearerChallenge() throws Exception {
		mockMvc.perform(get("/api/v1/probe"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Authentication is required"));
	}

	@Test
	void aValidTokenReachesTheEndpoint() throws Exception {
		mockMvc.perform(get("/api/v1/probe").header("Authorization", bearer(userToken())))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/probe").header("Authorization", bearer(userToken())))
				.andExpect(status().isOk()); // no CSRF token needed for bearer calls
	}

	@Test
	void aTamperedTokenIs401AndTheReasonIsNotRevealed() throws Exception {
		String token = userToken();
		String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");

		mockMvc.perform(get("/api/v1/probe").header("Authorization", bearer(tampered)))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
				.andExpect(jsonPath("$.message").value("The access token is invalid or has expired"));
	}

	@Test
	void aTokenSignedWithAnotherSecretIs401() throws Exception {
		SecurityProperties other = JwtServiceTest.properties(
				java.util.Base64.getEncoder().encodeToString("another-test-only-secret-key-32b".getBytes()), "archyra");
		String forged = new JwtService(new JwtConfig().jwtEncoder(other), other, Clock.systemUTC())
				.issue(UUID.randomUUID(), List.of("ADMIN")).value();

		mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", bearer(forged)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anExpiredTokenIs401() throws Exception {
		Clock longAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
		String expired = new JwtService(jwtEncoder, properties, longAgo).issue(UUID.randomUUID(), List.of("USER")).value();

		mockMvc.perform(get("/api/v1/probe").header("Authorization", bearer(expired)))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
	}

	@Test
	void aTokenInTheQueryStringIsIgnored() throws Exception {
		mockMvc.perform(get("/api/v1/probe").param("access_token", userToken()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void otherAuthenticationSchemesAreNotAccepted() throws Exception {
		mockMvc.perform(get("/api/v1/probe").header("Authorization", "Basic dXNlcjpwYXNz"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void adminRoutesNeedTheAdminRole() throws Exception {
		mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", bearer(userToken())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
				.andExpect(jsonPath("$.message").value("You do not have permission to do this"));
		mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", bearer(adminToken())))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/admin/probe"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void theAuthEndpointsArePublicExceptMe() throws Exception {
		mockMvc.perform(get("/api/v1/auth/open")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(userToken())))
				.andExpect(status().isOk());
	}

	@Test
	void everythingOutsideTheApiIsDenied() throws Exception {
		mockMvc.perform(get("/elsewhere")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/elsewhere").header("Authorization", bearer(adminToken())))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/actuator/health").header("Authorization", bearer(adminToken())))
				.andExpect(status().isForbidden());
	}

	@Test
	void theApiCreatesNoSession() throws Exception {
		var result = mockMvc.perform(get("/api/v1/probe").header("Authorization", bearer(userToken()))).andReturn();

		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(result.getResponse().getCookies()).isEmpty();
	}

	@Test
	void anAllowedOriginGetsCorsHeadersWithCredentials() throws Exception {
		mockMvc.perform(options("/api/v1/probe")
						.header("Origin", "http://localhost:5173")
						.header("Access-Control-Request-Method", "GET")
						.header("Access-Control-Request-Headers", "authorization,x-xsrf-token"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	@Test
	void aDisallowedOriginIsRefused() throws Exception {
		mockMvc.perform(options("/api/v1/probe")
						.header("Origin", "https://evil.example")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
		mockMvc.perform(get("/api/v1/probe").header("Origin", "https://evil.example")
						.header("Authorization", bearer(userToken())))
				.andExpect(status().isForbidden());
	}

	@Test
	void securityHeadersAreSet() throws Exception {
		mockMvc.perform(get("/api/v1/probe").secure(true).header("Authorization", bearer(userToken())))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
				.andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
	}

	@Test
	void errorResponsesCarryTheHeadersToo() throws Exception {
		mockMvc.perform(get("/api/v1/probe"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"));
	}
}
