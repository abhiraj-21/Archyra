package com.abhiraj.archyra.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;

/**
 * The {@code redirect_uri} the providers see, over real HTTP so Tomcat's RemoteIpValve (the forwarded-header handling
 * behind {@code server.forward-headers-strategy=native}) is in the path; MockMvc skips it.
 *
 * <p>In development the Vite proxy keeps the browser's {@code Host} ({@code changeOrigin: false}) and adds
 * {@code X-Forwarded-For/Proto/Port}. A raw socket is used because {@code java.net.http.HttpClient} refuses to set
 * {@code Host}. Redirects are never followed: the {@code Location} header is the thing under test, and it points at
 * the real provider.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OAuth2RedirectUriHttpTest {

	@LocalServerPort
	int port;

	private record Response(int status, String location) {
	}

	private Response get(String path, List<String> headers) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n");
			headers.forEach(h -> request.append(h).append("\r\n"));
			request.append("Connection: close\r\n\r\n");
			socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			ByteArrayOutputStream raw = new ByteArrayOutputStream();
			socket.getInputStream().transferTo(raw);
			String text = raw.toString(StandardCharsets.ISO_8859_1);
			String[] lines = text.split("\r\n");
			int status = Integer.parseInt(lines[0].split(" ")[1]);
			String location = null;
			for (String line : lines) {
				if (line.regionMatches(true, 0, "Location:", 0, "Location:".length())) {
					location = line.substring("Location:".length()).trim();
				}
			}
			return new Response(status, location);
		}
	}

	private static Optional<String> redirectUri(Response response) {
		assertThat(response.location()).isNotNull();
		String raw = UriComponentsBuilder.fromUri(URI.create(response.location())).build().getQueryParams().getFirst("redirect_uri");
		return Optional.ofNullable(raw).map(v -> UriUtils.decode(v, StandardCharsets.UTF_8));
	}

	private static final List<String> VITE_PROXY_HEADERS = List.of("Host: localhost:5173", "X-Forwarded-For: 127.0.0.1",
			"X-Forwarded-Proto: http", "X-Forwarded-Port: 5173");

	@Test
	void throughTheDevProxyGoogleGetsTheFrontendOrigin() throws Exception {
		Response response = get("/oauth2/authorization/google", VITE_PROXY_HEADERS);

		assertThat(response.status()).isEqualTo(302);
		assertThat(response.location()).startsWith("https://accounts.google.com/");
		assertThat(redirectUri(response)).contains("http://localhost:5173/login/oauth2/code/google");
	}

	@Test
	void throughTheDevProxyGithubGetsTheFrontendOrigin() throws Exception {
		Response response = get("/oauth2/authorization/github", VITE_PROXY_HEADERS);

		assertThat(response.status()).isEqualTo(302);
		assertThat(response.location()).startsWith("https://github.com/login/oauth/authorize");
		assertThat(redirectUri(response)).contains("http://localhost:5173/login/oauth2/code/github");
	}

	@Test
	void aForwardedHostAndPortAreHonouredWhenTheProxyDoesNotKeepTheHostHeader() throws Exception {
		List<String> headers = List.of("Host: localhost:" + port, "X-Forwarded-For: 127.0.0.1", "X-Forwarded-Proto: http",
				"X-Forwarded-Host: localhost:5173", "X-Forwarded-Port: 5173");

		assertThat(redirectUri(get("/oauth2/authorization/github", headers)))
				.contains("http://localhost:5173/login/oauth2/code/github");
	}

	@Test
	void withoutAProxyTheRedirectUriIsTheBackendsOwnOrigin() throws Exception {
		List<String> direct = List.of("Host: localhost:" + port);

		assertThat(redirectUri(get("/oauth2/authorization/google", direct)))
				.contains("http://localhost:" + port + "/login/oauth2/code/google");
		assertThat(redirectUri(get("/oauth2/authorization/github", direct)))
				.contains("http://localhost:" + port + "/login/oauth2/code/github");
	}

	@Test
	void anUntrustedClientCannotChooseTheRedirectUriByHeaderAlone() throws Exception {
		// The trusted-proxy check is on the connecting address; the test client is loopback, so it is trusted.
		// What must never happen is a redirect_uri taken from a request parameter.
		Response response = get("/oauth2/authorization/google?redirect_uri=https://evil.example/cb", List.of("Host: localhost:5173"));

		assertThat(redirectUri(response).orElseThrow()).doesNotContain("evil.example");
	}
}
