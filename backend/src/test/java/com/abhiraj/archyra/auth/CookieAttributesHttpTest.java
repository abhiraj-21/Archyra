package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

/**
 * Cookie attributes as a browser receives them from the real server. MockMvc does not render attributes that the
 * servlet container adds (such as SameSite on the XSRF cookie), so this one goes over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CookieAttributesHttpTest {

	@LocalServerPort
	int port;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void theXsrfCookieIsReadableByScriptsAndSameSiteStrict() throws Exception {
		HttpResponse<String> response = http.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/csrf")).build(),
				HttpResponse.BodyHandlers.ofString());

		String cookie = response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN="))
				.findFirst().orElseThrow();
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(cookie).contains("Path=/", "SameSite=Strict").doesNotContain("HttpOnly");
	}

	@Test
	void theRefreshCookieIsHttpOnlySameSiteStrictAndScopedToTheAuthPath() throws Exception {
		String email = "cookie-" + UUID.randomUUID() + "@example.com";
		User user = userService.registerIfAbsent(email, "Cookie", passwordEncoder.encode("correct horse battery"), Instant.now())
				.orElseThrow();
		user.markEmailVerified();
		userService.save(user);

		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().allValues("Set-Cookie")).hasSize(1).first().asString()
				.contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=604800");
	}
}
