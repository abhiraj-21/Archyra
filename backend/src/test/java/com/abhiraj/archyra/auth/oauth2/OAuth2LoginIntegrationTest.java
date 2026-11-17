package com.abhiraj.archyra.auth.oauth2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.auth.ClientInfo;
import com.abhiraj.archyra.auth.RefreshTokenService;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.Provider;
import com.abhiraj.archyra.user.Role;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import jakarta.servlet.http.Cookie;

/**
 * The whole OAuth2 handshake through the real filter chains, with WireMock standing in for Google (a signed ID token,
 * a JWKS and a user-info endpoint) and GitHub (token, profile and emails endpoints).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class OAuth2LoginIntegrationTest {

	private static final String SUCCESS = "http://localhost:5173/auth/callback";
	private static final String FAILURE = "http://localhost:5173/login?error=";
	private static final String PASSWORD = "correct horse battery";
	private static final String GOOGLE_ISSUER = "https://accounts.google.com";

	private static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort());
	private static final RSAKey KEY;

	static {
		try {
			KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
		}
		catch (Exception ex) {
			throw new ExceptionInInitializerError(ex);
		}
		PROVIDER.start();
	}

	@DynamicPropertySource
	static void providers(DynamicPropertyRegistry registry) {
		String base = PROVIDER.baseUrl();
		registry.add("spring.security.oauth2.client.provider.google.authorization-uri", () -> base + "/google/auth");
		registry.add("spring.security.oauth2.client.provider.google.token-uri", () -> base + "/google/token");
		registry.add("spring.security.oauth2.client.provider.google.jwk-set-uri", () -> base + "/google/jwks");
		registry.add("spring.security.oauth2.client.provider.google.user-info-uri", () -> base + "/google/userinfo");
		registry.add("spring.security.oauth2.client.provider.github.authorization-uri", () -> base + "/github/auth");
		registry.add("spring.security.oauth2.client.provider.github.token-uri", () -> base + "/github/token");
		registry.add("spring.security.oauth2.client.provider.github.user-info-uri", () -> base + "/github/user");
		registry.add("app.github.emails-uri", () -> base + "/github/user/emails");
	}

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	RefreshTokenService refreshTokens;
	@Autowired
	RecordingEmailService mails;

	@BeforeEach
	void resetProvider() throws Exception {
		PROVIDER.resetAll();
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/google/jwks")).willReturn(json(new JWKSet(KEY.toPublicJWK()).toString())));
	}

	// ---- fake providers -----------------------------------------------------------------------------------

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private static String uniqueEmail() {
		return "social-" + UUID.randomUUID() + "@example.com";
	}

	/** Tells the fake Google what the user "is": the token endpoint then answers with an ID token carrying the nonce. */
	private void googleWillAnswer(String nonce, String sub, String email, Boolean emailVerified, String name) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(GOOGLE_ISSUER).audience("test-google-client")
				.subject(sub).claim("email", email).claim("name", name).claim("nonce", nonce)
				.issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)));
		if (emailVerified != null) {
			claims.claim("email_verified", emailVerified);
		}
		SignedJWT idToken = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
		idToken.sign(new RSASSASigner(KEY));
		PROVIDER.stubFor(WireMock.post(urlEqualTo("/google/token")).willReturn(json("""
				{"access_token":"google-access","token_type":"Bearer","expires_in":3600,
				 "scope":"openid profile email","id_token":"%s"}""".formatted(idToken.serialize()))));
		String userInfo = new com.nimbusds.jose.shaded.gson.Gson().toJson(userInfoClaims(sub, email, emailVerified, name));
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/google/userinfo")).willReturn(json(userInfo)));
	}

	private static Map<String, Object> userInfoClaims(String sub, String email, Boolean emailVerified, String name) {
		var claims = new java.util.LinkedHashMap<String, Object>();
		claims.put("sub", sub);
		claims.put("email", email);
		claims.put("name", name);
		if (emailVerified != null) {
			claims.put("email_verified", emailVerified);
		}
		return claims;
	}

	private void githubWillAnswer(long id, String login, String name, String emailsJson) {
		PROVIDER.stubFor(WireMock.post(urlEqualTo("/github/token")).willReturn(json(
				"{\"access_token\":\"gh-token\",\"token_type\":\"bearer\",\"scope\":\"read:user,user:email\"}")));
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/github/user")).willReturn(json(
				"{\"id\":" + id + ",\"login\":\"" + login + "\",\"name\":" + (name == null ? "null" : "\"" + name + "\"")
						+ ",\"email\":\"public-" + login + "@example.com\"}")));
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/github/user/emails")).willReturn(json(emailsJson)));
	}

	// ---- driving the handshake ------------------------------------------------------------------------------

	private record Started(MockHttpSession session, String state, String nonce, String location) {
	}

	private Started start(String registrationId) throws Exception {
		return start(registrationId, "");
	}

	private Started start(String registrationId, String query) throws Exception {
		MvcResult result = mockMvc.perform(get("/oauth2/authorization/" + registrationId + query)).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(302);
		String location = result.getResponse().getRedirectedUrl();
		var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
		return new Started((MockHttpSession) result.getRequest().getSession(false), decoded(params.getFirst("state")),
				decoded(params.getFirst("nonce")), location);
	}

	/** Query values in the redirect are percent-encoded; the provider would send them back decoded. */
	private static String decoded(String value) {
		return value == null ? null : org.springframework.web.util.UriUtils.decode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	private MvcResult callback(String registrationId, Started started) throws Exception {
		return mockMvc.perform(get("/login/oauth2/code/" + registrationId).param("code", "the-code").param("state", started.state())
				.session(started.session())).andReturn();
	}

	private MvcResult googleLogin(String sub, String email, Boolean verified) throws Exception {
		Started started = start("google");
		googleWillAnswer(started.nonce(), sub, email, verified, "Ada Lovelace");
		return callback("google", started);
	}

	private MvcResult githubLogin(long id, String login, String emailsJson) throws Exception {
		Started started = start("github");
		githubWillAnswer(id, login, "Octo Cat", emailsJson);
		return callback("github", started);
	}

	private static String refreshCookie(MvcResult result) {
		return result.getResponse().getHeaders("Set-Cookie").stream().filter(c -> c.startsWith("refresh_token="))
				.map(c -> c.substring("refresh_token=".length(), c.indexOf(';'))).findFirst().orElse(null);
	}

	private MvcResult refresh(String refreshToken) throws Exception {
		String xsrf = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN").getValue();
		return mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", refreshToken),
				new Cookie("XSRF-TOKEN", xsrf)).header("X-XSRF-TOKEN", xsrf)).andReturn();
	}

	private MvcResult passwordLogin(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
	}

	private void assertFailure(MvcResult result, String code) {
		assertThat(result.getResponse().getStatus()).isEqualTo(302);
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(FAILURE + code);
		assertThat(refreshCookie(result)).isNull();
	}

	private User verifiedPasswordUser(String email) {
		User user = userService.registerIfAbsent(email, "Password User", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	// ---- Google: new, returning, linked ---------------------------------------------------------------------

	@Test
	void aNewGoogleUserIsCreatedAndSignedInWithoutAnyTokenInTheUrl() throws Exception {
		String email = uniqueEmail();
		int mailsBefore = mails.total();

		Started started = start("google");
		assertThat(started.location()).startsWith(PROVIDER.baseUrl() + "/google/auth")
				.contains("scope=openid%20profile%20email", "response_type=code", "client_id=test-google-client")
				.contains("redirect_uri=http://localhost/login/oauth2/code/google");
		googleWillAnswer(started.nonce(), "g-" + UUID.randomUUID(), email, true, "Ada Lovelace");

		MvcResult result = callback("google", started);

		assertThat(result.getResponse().getStatus()).isEqualTo(302);
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS); // no query, no fragment, no token
		String cookie = result.getResponse().getHeaders("Set-Cookie").stream().filter(c -> c.startsWith("refresh_token=")).findFirst().orElseThrow();
		assertThat(cookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=604800");
		assertThat(result.getResponse().getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("JSESSIONID=;") && c.contains("Max-Age=0"));
		assertThat(started.session().isInvalid()).isTrue();

		User user = userService.findByEmail(email).orElseThrow();
		assertThat(user.isEmailVerified()).isTrue();
		assertThat(user.getRole()).isEqualTo(Role.USER);
		assertThat(user.getPlan()).isEqualTo(Plan.FREE);
		assertThat(user.hasPassword()).isFalse();
		assertThat(user.getName()).isEqualTo("Ada Lovelace");
		assertThat(user.getLastLoginAt()).isNotNull();
		assertThat(userService.linkedProviders(user.getId())).containsExactly(Provider.GOOGLE);
		assertThat(mails.total()).isEqualTo(mailsBefore);

		// the SPA callback page does what it does after any login: refresh, then use the access token
		MvcResult refreshed = refresh(refreshCookie(result));
		assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
		String access = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");
		String me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access)).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(me, "$.email")).isEqualTo(email);
		assertThat((List<String>) JsonPath.read(me, "$.linkedProviders")).containsExactly("GOOGLE");
		assertThat((String) JsonPath.read(me, "$.plan")).isEqualTo("FREE");
	}

	@Test
	void aReturningUserIsMatchedByProviderIdEvenIfTheEmailChanged() throws Exception {
		String sub = "g-" + UUID.randomUUID();
		String firstEmail = uniqueEmail();
		String newEmail = uniqueEmail();
		googleLogin(sub, firstEmail, true);
		User first = userService.findByEmail(firstEmail).orElseThrow();

		MvcResult second = googleLogin(sub, newEmail, true);

		assertThat(second.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS);
		assertThat(userService.findByEmail(newEmail)).isEmpty(); // no second account
		assertThat(userService.findIdentity(Provider.GOOGLE, sub).orElseThrow().getUserId()).isEqualTo(first.getId());
	}

	@Test
	void aVerifiedPasswordAccountWithTheSameEmailIsLinkedAndKeepsItsPassword() throws Exception {
		String email = uniqueEmail();
		User existing = verifiedPasswordUser(email);
		String sub = "g-" + UUID.randomUUID();

		MvcResult result = googleLogin(sub, email, true);

		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS);
		assertThat(userService.findIdentity(Provider.GOOGLE, sub).orElseThrow().getUserId()).isEqualTo(existing.getId());
		assertThat(userService.findByEmail(email).orElseThrow().hasPassword()).isTrue();
		assertThat(passwordLogin(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void anUnverifiedPasswordAccountIsTakenOverItsPasswordRemovedAndOldSessionsEnded() throws Exception {
		String email = uniqueEmail();
		User squatter = userService.registerIfAbsent(email, "Squatter", passwordEncoder.encode("attackers password"), Instant.now()).orElseThrow();
		String stolenSession = refreshTokens.startFamily(squatter.getId(), new ClientInfo("203.0.113.5", "attacker")).rawToken();
		String sub = "g-" + UUID.randomUUID();

		MvcResult result = googleLogin(sub, email, true);

		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS);
		User user = userService.findByEmail(email).orElseThrow();
		assertThat(user.getId()).isEqualTo(squatter.getId());
		assertThat(user.hasPassword()).isFalse();
		assertThat(user.isEmailVerified()).isTrue();
		assertThat(passwordLogin(email, "attackers password").getResponse().getStatus()).isEqualTo(401);
		assertThat(refresh(stolenSession).getResponse().getStatus()).isEqualTo(401);
		assertThat(refresh(refreshCookie(result)).getResponse().getStatus()).isEqualTo(200); // the real owner's session works
	}

	// ---- Google: refusals -------------------------------------------------------------------------------------

	@Test
	void googleWithAnUnverifiedEmailIsRejected() throws Exception {
		String email = uniqueEmail();

		MvcResult result = googleLogin("g-" + UUID.randomUUID(), email, false);

		assertFailure(result, "oauth_email_unverified");
		assertThat(userService.findByEmail(email)).isEmpty();
	}

	@Test
	void googleWithoutTheEmailVerifiedClaimIsRejected() throws Exception {
		String email = uniqueEmail();

		assertFailure(googleLogin("g-" + UUID.randomUUID(), email, null), "oauth_email_unverified");
		assertThat(userService.findByEmail(email)).isEmpty();
	}

	@Test
	void aDisabledAccountCannotSignInThroughGoogle() throws Exception {
		String email = uniqueEmail();
		User user = verifiedPasswordUser(email);
		user.setEnabled(false);
		userService.save(user);

		assertFailure(googleLogin("g-" + UUID.randomUUID(), email, true), "oauth_account_disabled");
		assertThat(userService.linkedProviders(user.getId())).isEmpty();
	}

	@Test
	void anAnswerWithAnotherNonceIsRejected() throws Exception {
		Started started = start("google");
		googleWillAnswer("not-the-nonce-we-sent", "g-" + UUID.randomUUID(), uniqueEmail(), true, "Eve");

		assertFailure(callback("google", started), "oauth_failed");
	}

	// ---- GitHub -------------------------------------------------------------------------------------------------

	@Test
	void aNewGithubUserIsCreatedFromThePrimaryVerifiedEmail() throws Exception {
		String email = uniqueEmail();
		long id = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);

		MvcResult result = githubLogin(id, "octo", """
				[{"email":"%s","primary":false,"verified":true},
				 {"email":"%s","primary":true,"verified":true,"visibility":"private"}]""".formatted(uniqueEmail(), email));

		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS);
		User user = userService.findByEmail(email).orElseThrow();
		assertThat(user.getName()).isEqualTo("Octo Cat");
		assertThat(user.isEmailVerified()).isTrue();
		assertThat(userService.findIdentity(Provider.GITHUB, String.valueOf(id)).orElseThrow().getUserId()).isEqualTo(user.getId());
		assertThat(refreshCookie(result)).isNotNull();
	}

	@Test
	void githubWithoutAPrimaryVerifiedEmailIsRejectedEvenIfTheProfileShowsAnEmail() throws Exception {
		long id = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);

		MvcResult result = githubLogin(id, "ghost", """
				[{"email":"unverified@example.com","primary":true,"verified":false}]""");

		assertFailure(result, "oauth_email_unverified");
		assertThat(userService.findByEmail("public-ghost@example.com")).isEmpty(); // the profile's own email field
		assertThat(userService.findByEmail("unverified@example.com")).isEmpty();
		assertThat(userService.findIdentity(Provider.GITHUB, String.valueOf(id))).isEmpty();
	}

	@Test
	void whenGithubCannotListEmailsTheLoginFailsGenerically() throws Exception {
		Started started = start("github");
		githubWillAnswer(777, "flaky", "Flaky", "[]");
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/github/user/emails")).willReturn(aResponse().withStatus(503)));

		assertFailure(callback("github", started), "oauth_failed");
	}

	// ---- the handshake itself ----------------------------------------------------------------------------------

	@Test
	void theUserDecliningAtTheProviderIsACancellation() throws Exception {
		Started started = start("google");

		MvcResult result = mockMvc.perform(get("/login/oauth2/code/google").param("error", "access_denied")
				.param("error_description", "user said no").param("state", started.state()).session(started.session())).andReturn();

		assertFailure(result, "oauth_cancelled");
		assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("said");
		assertThat(started.session().isInvalid()).isTrue();
	}

	@Test
	void aWrongStateIsRejected() throws Exception {
		Started started = start("google");
		googleWillAnswer(started.nonce(), "g-" + UUID.randomUUID(), uniqueEmail(), true, "Eve");

		MvcResult result = mockMvc.perform(get("/login/oauth2/code/google").param("code", "c").param("state", "forged-state")
				.session(started.session())).andReturn();

		assertFailure(result, "oauth_failed");
	}

	@Test
	void aCallbackWithoutAHandshakeSessionIsRejected() throws Exception {
		MvcResult result = mockMvc.perform(get("/login/oauth2/code/google").param("code", "c").param("state", "anything")).andReturn();

		assertFailure(result, "oauth_failed");
	}

	@Test
	void aSuccessfulCallbackCannotBeReplayed() throws Exception {
		String email = uniqueEmail();
		Started started = start("google");
		googleWillAnswer(started.nonce(), "g-" + UUID.randomUUID(), email, true, "Ada");
		assertThat(callback("google", started).getResponse().getRedirectedUrl()).isEqualTo(SUCCESS);

		MvcResult replay = mockMvc.perform(get("/login/oauth2/code/google").param("code", "the-code").param("state", started.state())
				.session(started.session())).andReturn();

		assertFailure(replay, "oauth_failed");
	}

	@Test
	void requestParametersCannotChangeWhereTheBrowserIsSent() throws Exception {
		String email = uniqueEmail();
		Started started = start("google", "?redirect_uri=https://evil.example/steal&continue=https://evil.example&next=//evil.example&returnTo=https://evil.example");
		assertThat(started.location()).doesNotContain("evil.example");
		googleWillAnswer(started.nonce(), "g-" + UUID.randomUUID(), email, true, "Ada");

		MvcResult result = mockMvc.perform(get("/login/oauth2/code/google").param("code", "c").param("state", started.state())
				.param("redirect_uri", "https://evil.example").param("continue", "https://evil.example")
				.session(started.session())).andReturn();

		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS);
	}

	@Test
	void theHandshakeSessionHoldsNoSignedInUser() throws Exception {
		Started started = start("google");
		googleWillAnswer(started.nonce(), "g-" + UUID.randomUUID(), uniqueEmail(), true, "Ada");

		MvcResult result = callback("google", started);

		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS);
		// after the redirect the browser is not signed in to anything but the refresh cookie flow
		mockMvc.perform(get("/api/v1/auth/me").session(started.session())).andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
	}
}
