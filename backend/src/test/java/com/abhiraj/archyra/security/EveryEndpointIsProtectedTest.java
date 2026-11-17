package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.user.User;

/**
 * A guard for every present and future endpoint: it reads the real handler mappings, so a controller added later is
 * checked without anyone remembering to. Everything under {@code /api/v1} must answer 401 without a token, except the
 * explicit public list below, and everything under {@code /api/v1/admin} must answer 403 to an ordinary user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class EveryEndpointIsProtectedTest {

	/** The only endpoints reachable without an access token. Refresh and logout use the cookie plus CSRF instead. */
	private static final Set<String> PUBLIC = Set.of(
			"POST /api/v1/auth/register",
			"POST /api/v1/auth/login",
			"POST /api/v1/auth/refresh",
			"POST /api/v1/auth/logout",
			"GET /api/v1/auth/csrf",
			"POST /api/v1/auth/verify-email",
			"POST /api/v1/auth/resend-verification",
			"POST /api/v1/auth/forgot-password",
			"POST /api/v1/auth/reset-password");

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JwtService jwtService;
	@Autowired
	RequestMappingHandlerMapping handlerMapping;

	private record Endpoint(HttpMethod method, String pattern) {

		String key() {
			return method + " " + pattern;
		}

		String url() {
			return pattern.replaceAll("\\{[^}]+}", "x");
		}
	}

	private List<Endpoint> apiEndpoints() {
		List<Endpoint> endpoints = new ArrayList<>();
		for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
			Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
			Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
			for (String pattern : patterns) {
				if (pattern.startsWith("/api/v1")) {
					for (var method : methods.isEmpty() ? Set.of(org.springframework.web.bind.annotation.RequestMethod.GET) : methods) {
						endpoints.add(new Endpoint(HttpMethod.valueOf(method.name()), pattern));
					}
				}
			}
		}
		return endpoints;
	}

	@Test
	void theGuardActuallySeesTheApi() {
		Set<String> found = apiEndpoints().stream().map(Endpoint::key).collect(Collectors.toCollection(TreeSet::new));

		assertThat(found).hasSizeGreaterThanOrEqualTo(25);
		assertThat(found).contains("POST /api/v1/analyses", "GET /api/v1/analyses/{analysisId}/graph", "GET /api/v1/admin/users");
		assertThat(found).containsAll(PUBLIC); // a public entry that no longer exists is a stale list
	}

	@Test
	void everyEndpointOutsideThePublicListNeedsAnAccessToken() throws Exception {
		List<String> openByMistake = new ArrayList<>();

		for (Endpoint endpoint : apiEndpoints()) {
			if (PUBLIC.contains(endpoint.key())) {
				continue;
			}
			int status = mockMvc.perform(request(endpoint.method(), endpoint.url())).andReturn().getResponse().getStatus();
			if (status != 401) {
				openByMistake.add(endpoint.key() + " -> " + status);
			}
		}

		assertThat(openByMistake).as("endpoints reachable without a token").isEmpty();
	}

	@Test
	void everyAdminEndpointRefusesAnOrdinaryUser() throws Exception {
		User user = User.registered("guard@example.com", "Guard", null, Instant.now());
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		String token = "Bearer " + jwtService.issueFor(user).value();
		List<String> reachable = new ArrayList<>();
		int checked = 0;

		for (Endpoint endpoint : apiEndpoints()) {
			if (endpoint.pattern().startsWith("/api/v1/admin")) {
				checked++;
				int status = mockMvc.perform(request(endpoint.method(), endpoint.url()).header("Authorization", token))
						.andReturn().getResponse().getStatus();
				if (status != 403) {
					reachable.add(endpoint.key() + " -> " + status);
				}
			}
		}

		assertThat(checked).isGreaterThanOrEqualTo(3);
		assertThat(reachable).as("admin endpoints an ordinary user can reach").isEmpty();
	}

	@Test
	void theDocumentedPublicEndpointsReallyWorkWithoutAToken() throws Exception {
		int csrf = mockMvc.perform(request(HttpMethod.GET, "/api/v1/auth/csrf")).andReturn().getResponse().getStatus();
		int verify = mockMvc.perform(request(HttpMethod.POST, "/api/v1/auth/verify-email").contentType("application/json")
				.content("{\"token\":\"nope\"}")).andReturn().getResponse().getStatus();

		assertThat(csrf).isEqualTo(200);
		assertThat(verify).isEqualTo(400); // reached the controller, which refused the token, not the filter
	}
}
