package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.abhiraj.archyra.TestcontainersConfiguration;

/**
 * Over real HTTP, so Tomcat's RemoteIpValve is in the path: behind a trusted proxy (here the loopback test client,
 * which is in the default internal-proxy range) the rate limit counts the forwarded client, not the proxy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.rate-limit.login-per-ip-per-minute=3")
class ForwardedClientIpHttpTest {

	@LocalServerPort
	int port;

	private final HttpClient http = HttpClient.newHttpClient();

	private int login(String forwardedFor) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
				.header("Content-Type", "application/json").header("X-Forwarded-For", forwardedFor)
				.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.com\",\"password\":\"not the password\"}")).build();
		return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
	}

	@Test
	void theClientBehindATrustedProxyIsTheOneWhoIsCounted() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertThat(login("198.51.100.77")).isEqualTo(401);
		}

		assertThat(login("198.51.100.77")).isEqualTo(429);
		assertThat(login("198.51.100.78")).isEqualTo(401); // another client behind the same proxy is not blamed
	}
}
