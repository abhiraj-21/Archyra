package com.abhiraj.archyra.auth.oauth2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.common.LinkedIdentityRuleException;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.user.IdentityLinkService;
import com.abhiraj.archyra.user.IdentityLinkService.ProviderIdentityDetails;
import com.abhiraj.archyra.user.Provider;
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
 * Account linking from Settings, through the real filter chains: the link intent and its browser-binding cookie, the
 * OAuth2 handshake in link mode (WireMock stands in for Google and GitHub, as in {@link OAuth2LoginIntegrationTest}),
 * the handler that attaches the identity, and the list/unlink endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class AccountLinkingIntegrationTest {

	private static final String SETTINGS = "http://localhost:5173/settings";
	private static final String LOGIN_SUCCESS = "http://localhost:5173/auth/callback";
	private static final String PASSWORD = "correct horse battery";
	private static final String GOOGLE_ISSUER = "https://accounts.google.com";

	private static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort());
	private static final RSAKey KEY;

	static {
		try {
			KEY = new RSAKeyGenerator(2048).keyID("link-test-key").generate();
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
	IdentityLinkService links;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JwtService jwtService;
	@Autowired
	RecordingEmailService mails;
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void resetProvider() throws Exception {
		PROVIDER.resetAll();
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/google/jwks")).willReturn(json(new JWKSet(KEY.toPublicJWK()).toString())));
		mails.failIdentityNotices(false);
	}

	// ---- fixtures ------------------------------------------------------------------------------------------------

	private static String uniqueEmail() {
		return "link-" + UUID.randomUUID() + "@example.com";
	}

	private static String sub() {
		return "g-" + UUID.randomUUID();
	}

	private User passwordUser(String email) {
		User user = userService.registerIfAbsent(email, "Password User", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	/** A verified account that has only ever used a provider: no password. */
	private User providerOnlyUser(Provider provider, String providerUserId) {
		User user = userService.createSocialUser(uniqueEmail(), "Provider User");
		userService.link(user, provider, providerUserId, user.getEmail());
		return user;
	}

	private String bearer(User user) {
		return "Bearer " + jwtService.issueFor(user).value();
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private void googleWillAnswer(String nonce, String sub, String email, Boolean emailVerified) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(GOOGLE_ISSUER).audience("test-google-client")
				.subject(sub).claim("name", "Linked Person").claim("nonce", nonce)
				.issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)));
		if (email != null) {
			claims.claim("email", email);
		}
		if (emailVerified != null) {
			claims.claim("email_verified", emailVerified);
		}
		SignedJWT idToken = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
		idToken.sign(new RSASSASigner(KEY));
		PROVIDER.stubFor(WireMock.post(urlEqualTo("/google/token")).willReturn(json("""
				{"access_token":"google-access","token_type":"Bearer","expires_in":3600,
				 "scope":"openid profile email","id_token":"%s"}""".formatted(idToken.serialize()))));
		var info = new java.util.LinkedHashMap<String, Object>();
		info.put("sub", sub);
		if (email != null) {
			info.put("email", email);
		}
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/google/userinfo")).willReturn(json(new com.nimbusds.jose.shaded.gson.Gson().toJson(info))));
	}

	private void githubWillAnswer(long id, String login, String emailsJson) {
		PROVIDER.stubFor(WireMock.post(urlEqualTo("/github/token")).willReturn(json(
				"{\"access_token\":\"gh-token\",\"token_type\":\"bearer\",\"scope\":\"read:user,user:email\"}")));
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/github/user")).willReturn(json(
				"{\"id\":" + id + ",\"login\":\"" + login + "\",\"name\":\"Octo Cat\"}")));
		PROVIDER.stubFor(WireMock.get(urlEqualTo("/github/user/emails")).willReturn(json(emailsJson)));
	}

	// ---- driving the link ----------------------------------------------------------------------------------------

	/** What the SPA gets from {@code POST .../link-intent}: the URL it navigates to, and the cookie the browser keeps. */
	private record Intent(String url, String bindingCookie, String setCookieHeader) {

		String token() {
			return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("link_intent");
		}
	}

	private Intent intentFor(User user, String provider) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/users/me/linked-identities/" + provider + "/link-intent")
				.header("Authorization", bearer(user))).andReturn();
		assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
		String url = JsonPath.read(result.getResponse().getContentAsString(), "$.authorizationUrl");
		String header = result.getResponse().getHeaders("Set-Cookie").stream().filter(c -> c.startsWith("link_binding=")).findFirst().orElseThrow();
		return new Intent(url, header.substring("link_binding=".length(), header.indexOf(';')), header);
	}

	private record Started(MockHttpSession session, String state, String nonce, MvcResult result) {
	}

	/** The browser following the authorization URL (with the binding cookie unless told otherwise). */
	private MvcResult authorize(String url, String bindingCookie) throws Exception {
		var request = get(url);
		if (bindingCookie != null) {
			request.cookie(new Cookie("link_binding", bindingCookie));
		}
		return mockMvc.perform(request).andReturn();
	}

	private Started startLink(Intent intent) throws Exception {
		MvcResult result = authorize(intent.url(), intent.bindingCookie());
		assertThat(result.getResponse().getStatus()).isEqualTo(302);
		String location = result.getResponse().getRedirectedUrl();
		assertThat(location).startsWith(PROVIDER.baseUrl());
		var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
		return new Started((MockHttpSession) result.getRequest().getSession(false), decoded(params.getFirst("state")),
				decoded(params.getFirst("nonce")), result);
	}

	private static String decoded(String value) {
		return value == null ? null : org.springframework.web.util.UriUtils.decode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	private MvcResult callback(String registrationId, Started started) throws Exception {
		return mockMvc.perform(get("/login/oauth2/code/" + registrationId).param("code", "the-code").param("state", started.state())
				.session(started.session())).andReturn();
	}

	private MvcResult linkGoogle(User user, String sub, String email) throws Exception {
		Started started = startLink(intentFor(user, "google"));
		googleWillAnswer(started.nonce(), sub, email, true);
		return callback("google", started);
	}

	private MvcResult linkGithub(User user, long id, String login, String emailsJson) throws Exception {
		Started started = startLink(intentFor(user, "github"));
		githubWillAnswer(id, login, emailsJson);
		return callback("github", started);
	}

	private static void assertRedirect(MvcResult result, String expected) {
		assertThat(result.getResponse().getStatus()).isEqualTo(302);
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(expected);
	}

	/** A link never logs anyone in: no refresh cookie, no token, and the handshake session is gone. */
	private static void assertNoLoginArtifacts(MvcResult result) {
		assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("refresh_token="));
		assertThat(result.getResponse().getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("JSESSIONID=;") && c.contains("Max-Age=0"));
		assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("token");
	}

	// ---- the intent endpoint --------------------------------------------------------------------------------------

	@Test
	void theIntentIsAnAuthorizationUrlPlusAnHttpOnlyStrictBindingCookie() throws Exception {
		User user = passwordUser(uniqueEmail());

		Intent intent = intentFor(user, "google");

		assertThat(intent.url()).startsWith("/oauth2/authorization/google?link_intent=");
		assertThat(intent.token()).hasSizeGreaterThanOrEqualTo(40);
		assertThat(intent.setCookieHeader()).contains("HttpOnly", "SameSite=Strict", "Path=/oauth2/authorization", "Max-Age=300");
		assertThat(intent.bindingCookie()).isNotEqualTo(intent.token());
		// only hashes are stored
		Integer matching = jdbc.queryForObject("select count(*) from oauth_link_intents where token_hash = ? or binding_hash = ?", Integer.class,
				intent.token(), intent.bindingCookie());
		assertThat(matching).isZero();
	}

	@Test
	void anUnknownProviderIsAValidationErrorAndAnonymousCallersAreRefused() throws Exception {
		User user = passwordUser(uniqueEmail());

		MvcResult unknown = mockMvc.perform(post("/api/v1/users/me/linked-identities/facebook/link-intent").header("Authorization", bearer(user))).andReturn();
		assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
		assertThat((String) JsonPath.read(unknown.getResponse().getContentAsString(), "$.code")).isEqualTo("VALIDATION_ERROR");
		assertThat(unknown.getResponse().getContentAsString()).doesNotContain("facebook");

		assertThat(mockMvc.perform(post("/api/v1/users/me/linked-identities/google/link-intent")).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(get("/api/v1/users/me/linked-identities")).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(delete("/api/v1/users/me/linked-identities/google")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void startingALinkForAProviderThatIsAlreadyLinkedIsAConflict() throws Exception {
		User user = providerOnlyUser(Provider.GOOGLE, sub());

		MvcResult result = mockMvc.perform(post("/api/v1/users/me/linked-identities/google/link-intent").header("Authorization", bearer(user))).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code")).isEqualTo("PROVIDER_ALREADY_LINKED");
		assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("link_binding="));
	}

	// ---- linking: the happy paths --------------------------------------------------------------------------------

	@Test
	void googleWithADifferentEmailIsLinkedAndTheAccountEmailDoesNotChange() throws Exception {
		String accountEmail = uniqueEmail();
		String googleEmail = uniqueEmail();
		User user = passwordUser(accountEmail);
		String sub = sub();
		int mailsBefore = mails.total();

		Started started = startLink(intentFor(user, "google"));
		assertThat(started.result().getResponse().getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("link_binding=;") && c.contains("Max-Age=0"));
		googleWillAnswer(started.nonce(), sub, googleEmail, false); // the provider's email need not be verified
		MvcResult result = callback("google", started);

		assertRedirect(result, SETTINGS + "?link=ok&provider=google");
		assertNoLoginArtifacts(result);
		assertThat(started.session().isInvalid()).isTrue();
		assertThat(userService.findByEmail(accountEmail).orElseThrow().getId()).isEqualTo(user.getId());
		assertThat(userService.findByEmail(googleEmail)).isEmpty(); // no account was created or matched by that email
		assertThat(userService.findIdentity(Provider.GOOGLE, sub).orElseThrow().getUserId()).isEqualTo(user.getId());
		assertThat(userService.findByEmail(accountEmail).orElseThrow().hasPassword()).isTrue();

		String body = mockMvc.perform(get("/api/v1/users/me/linked-identities").header("Authorization", bearer(user))).andReturn().getResponse().getContentAsString();
		assertThat((Boolean) JsonPath.read(body, "$.hasPassword")).isTrue();
		assertThat((List<?>) JsonPath.read(body, "$.identities")).hasSize(1);
		assertThat((String) JsonPath.read(body, "$.identities[0].provider")).isEqualTo("google");
		assertThat((String) JsonPath.read(body, "$.identities[0].email")).isEqualTo(googleEmail);
		assertThat((Object) JsonPath.read(body, "$.identities[0].login")).isNull();
		assertThat((String) JsonPath.read(body, "$.identities[0].linkedAt")).isNotBlank();

		// one notification, to the account's own address, naming the provider, with no token
		assertThat(mails.total()).isEqualTo(mailsBefore + 1);
		RecordingEmailService.Sent notice = mails.lastTo(accountEmail);
		assertThat(notice.kind()).isEqualTo("IDENTITY_LINKED");
		assertThat(notice.providers()).containsExactly("Google");
		assertThat(notice.link()).isEqualTo("http://localhost:5173/forgot-password").doesNotContain("token");
	}

	@Test
	void afterLinkingTheUserCanSignInWithTheProviderAndReachTheSameAccount() throws Exception {
		User user = passwordUser(uniqueEmail());
		String sub = sub();
		assertRedirect(linkGoogle(user, sub, uniqueEmail()), SETTINGS + "?link=ok&provider=google");

		// an ordinary Google login (no link intent): the existing resolver finds the identity
		MvcResult start = mockMvc.perform(get("/oauth2/authorization/google")).andReturn();
		var params = UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl()).build().getQueryParams();
		googleWillAnswer(decoded(params.getFirst("nonce")), sub, uniqueEmail(), true);
		MvcResult login = mockMvc.perform(get("/login/oauth2/code/google").param("code", "c").param("state", decoded(params.getFirst("state")))
				.session((MockHttpSession) start.getRequest().getSession(false))).andReturn();

		assertRedirect(login, LOGIN_SUCCESS);
		assertThat(login.getResponse().getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("refresh_token="));
		assertThat(userService.findIdentity(Provider.GOOGLE, sub).orElseThrow().getUserId()).isEqualTo(user.getId());
	}

	@Test
	void githubStoresTheLoginHandleAndAnEmailIsNotRequired() throws Exception {
		User user = passwordUser(uniqueEmail());
		long id = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);

		MvcResult result = linkGithub(user, id, "octocat", "[]"); // no verified primary email at all

		assertRedirect(result, SETTINGS + "?link=ok&provider=github");
		assertNoLoginArtifacts(result);
		String body = mockMvc.perform(get("/api/v1/users/me/linked-identities").header("Authorization", bearer(user))).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(body, "$.identities[0].provider")).isEqualTo("github");
		assertThat((String) JsonPath.read(body, "$.identities[0].login")).isEqualTo("octocat");
		assertThat((Object) JsonPath.read(body, "$.identities[0].email")).isNull();
	}

	@Test
	void theIdentityGoesToTheIntentsUserNeverToTheAccountThatOwnsTheProvidersEmail() throws Exception {
		String sharedEmail = uniqueEmail();
		User emailOwner = passwordUser(sharedEmail);
		User linker = passwordUser(uniqueEmail());
		String sub = sub();

		MvcResult result = linkGoogle(linker, sub, sharedEmail); // Google says the email of someone else's account

		assertRedirect(result, SETTINGS + "?link=ok&provider=google");
		assertThat(userService.findIdentity(Provider.GOOGLE, sub).orElseThrow().getUserId()).isEqualTo(linker.getId());
		assertThat(userService.linkedProviders(emailOwner.getId())).isEmpty();
	}

	@Test
	void linkingTheSameIdentityAgainIsASuccessAndStaysASingleRow() throws Exception {
		User user = passwordUser(uniqueEmail());
		String sub = sub();
		Started started = startLink(intentFor(user, "google"));
		links.link(user.getId(), new ProviderIdentityDetails(Provider.GOOGLE, sub, "x@example.com", null)); // linked meanwhile
		googleWillAnswer(started.nonce(), sub, "x@example.com", true);

		MvcResult result = callback("google", started);

		assertRedirect(result, SETTINGS + "?link=ok&provider=google");
		assertThat(userService.linkedProviders(user.getId())).containsExactly(Provider.GOOGLE);
		assertThat(jdbc.queryForObject("select count(*) from linked_identities where user_id = ?", Integer.class, user.getId())).isEqualTo(1);
	}

	// ---- linking: refusals ----------------------------------------------------------------------------------------

	@Test
	void anIdentityLinkedToAnotherAccountIsBlockedWithoutChangingAnythingOrNamingTheOwner() throws Exception {
		String sub = sub();
		User owner = providerOnlyUser(Provider.GOOGLE, sub);
		User intruder = passwordUser(uniqueEmail());
		int mailsBefore = mails.total();

		MvcResult result = linkGoogle(intruder, sub, owner.getEmail());

		assertRedirect(result, SETTINGS + "?link_error=identity_already_linked");
		assertNoLoginArtifacts(result);
		assertThat(result.getResponse().getRedirectedUrl()).doesNotContain(owner.getEmail()).doesNotContain(owner.getId().toString());
		assertThat(userService.findIdentity(Provider.GOOGLE, sub).orElseThrow().getUserId()).isEqualTo(owner.getId());
		assertThat(userService.linkedProviders(intruder.getId())).isEmpty();
		assertThat(mails.total()).isEqualTo(mailsBefore);
	}

	@Test
	void aSecondDifferentAccountOfTheSameProviderIsBlocked() throws Exception {
		User user = passwordUser(uniqueEmail());
		Started started = startLink(intentFor(user, "google"));
		String first = sub();
		links.link(user.getId(), new ProviderIdentityDetails(Provider.GOOGLE, first, "a@example.com", null)); // linked meanwhile
		String second = sub();
		googleWillAnswer(started.nonce(), second, "b@example.com", true);

		MvcResult result = callback("google", started);

		assertRedirect(result, SETTINGS + "?link_error=provider_already_linked");
		assertThat(userService.findIdentity(Provider.GOOGLE, second)).isEmpty();
		assertThat(userService.findIdentity(Provider.GOOGLE, first).orElseThrow().getUserId()).isEqualTo(user.getId());
	}

	@Test
	void aDisabledAccountCannotLink() throws Exception {
		User user = passwordUser(uniqueEmail());
		Started started = startLink(intentFor(user, "google"));
		user.setEnabled(false);
		userService.save(user);
		String sub = sub();
		googleWillAnswer(started.nonce(), sub, uniqueEmail(), true);

		MvcResult result = callback("google", started);

		assertRedirect(result, SETTINGS + "?link_error=oauth_account_disabled");
		assertThat(userService.findIdentity(Provider.GOOGLE, sub)).isEmpty();
	}

	@Test
	void decliningAtTheProviderReturnsToSettingsAsCancelledAndEndsTheSession() throws Exception {
		User user = passwordUser(uniqueEmail());
		Started started = startLink(intentFor(user, "google"));

		MvcResult result = mockMvc.perform(get("/login/oauth2/code/google").param("error", "access_denied").param("state", started.state())
				.session(started.session())).andReturn();

		assertRedirect(result, SETTINGS + "?link_error=oauth_cancelled");
		assertThat(started.session().isInvalid()).isTrue();
		assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("refresh_token="));
	}

	@Test
	void aProviderFailureInLinkModeReturnsToSettingsNotToLogin() throws Exception {
		User user = passwordUser(uniqueEmail());
		Started started = startLink(intentFor(user, "google"));
		googleWillAnswer("not-the-nonce-we-sent", sub(), uniqueEmail(), true);

		assertRedirect(callback("google", started), SETTINGS + "?link_error=oauth_failed");
	}

	// ---- the intent: single use, expiry, provider, browser binding --------------------------------------------------

	@Test
	void anIntentWorksOnce() throws Exception {
		User user = passwordUser(uniqueEmail());
		Intent intent = intentFor(user, "google");
		startLink(intent);

		MvcResult replay = authorize(intent.url(), intent.bindingCookie());

		assertRedirect(replay, SETTINGS + "?link_error=link_expired");
	}

	@Test
	void anIntentExpiresAfterFiveMinutes() throws Exception {
		User user = passwordUser(uniqueEmail());
		Intent intent = intentFor(user, "google");
		jdbc.update("update oauth_link_intents set expires_at = now() - interval '1 second' where user_id = ?", user.getId());

		assertRedirect(authorize(intent.url(), intent.bindingCookie()), SETTINGS + "?link_error=link_expired");
	}

	@Test
	void theHandshakeItselfExpiresWithTheIntent() throws Exception {
		User user = passwordUser(uniqueEmail());
		Started started = startLink(intentFor(user, "google"));
		String sub = sub();
		googleWillAnswer(started.nonce(), sub, uniqueEmail(), true);
		// the user took too long at the provider: the deadline stored in the session has passed
		started.session().setAttribute(LinkContext.class.getName() + ".EXPIRES_AT", Instant.now().minusSeconds(1).toEpochMilli());

		assertRedirect(callback("google", started), SETTINGS + "?link_error=link_expired");
		assertThat(userService.findIdentity(Provider.GOOGLE, sub)).isEmpty();
	}

	@Test
	void anIntentIssuedForOneProviderDoesNotWorkForAnother() throws Exception {
		User user = passwordUser(uniqueEmail());
		Intent intent = intentFor(user, "google");

		MvcResult result = authorize(intent.url().replace("/google?", "/github?"), intent.bindingCookie());

		assertRedirect(result, SETTINGS + "?link_error=link_expired");
	}

	@Test
	void theUrlReplayedWithoutTheBindingCookieDoesNothingAndDoesNotBurnTheIntent() throws Exception {
		User user = passwordUser(uniqueEmail());
		Intent intent = intentFor(user, "google");

		// what an attacker's victim would do: open a crafted URL in a browser that never asked for it
		assertRedirect(authorize(intent.url(), null), SETTINGS + "?link_error=link_expired");
		assertRedirect(authorize(intent.url(), "a-different-binding-secret"), SETTINGS + "?link_error=link_expired");

		// the asking browser can still use it, once
		Started started = startLink(intent);
		assertThat(started.session()).isNotNull();
	}

	@Test
	void anUnknownOrOversizedIntentIsRejected() throws Exception {
		assertRedirect(authorize("/oauth2/authorization/google?link_intent=nope", "x"), SETTINGS + "?link_error=link_expired");
		assertRedirect(authorize("/oauth2/authorization/google?link_intent=" + "a".repeat(5000), "x"), SETTINGS + "?link_error=link_expired");
		assertRedirect(authorize("/oauth2/authorization/google?link_intent=", "x"), SETTINGS + "?link_error=link_expired");
	}

	@Test
	void aPlainLoginRequestClearsAStaleLinkContextSoItIsNeverTreatedAsALink() throws Exception {
		User user = passwordUser(uniqueEmail());
		Started started = startLink(intentFor(user, "google")); // link context is now in the session, never finished
		assertThat(LinkContext.read(started.session())).isPresent();

		// the same browser session starts an ordinary Google login
		MvcResult plain = mockMvc.perform(get("/oauth2/authorization/google").session(started.session())).andReturn();
		assertThat(LinkContext.read(started.session())).isEmpty();
		var params = UriComponentsBuilder.fromUriString(plain.getResponse().getRedirectedUrl()).build().getQueryParams();
		String sub = sub();
		String email = uniqueEmail();
		googleWillAnswer(decoded(params.getFirst("nonce")), sub, email, true);
		MvcResult login = mockMvc.perform(get("/login/oauth2/code/google").param("code", "c").param("state", decoded(params.getFirst("state")))
				.session(started.session())).andReturn();

		// a normal login: a new account for that email, a refresh cookie, and the original user is untouched
		assertRedirect(login, LOGIN_SUCCESS);
		assertThat(login.getResponse().getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("refresh_token="));
		assertThat(userService.findIdentity(Provider.GOOGLE, sub).orElseThrow().getUserId())
				.isEqualTo(userService.findByEmail(email).orElseThrow().getId())
				.isNotEqualTo(user.getId());
		assertThat(userService.linkedProviders(user.getId())).isEmpty();
	}

	// ---- unlink -----------------------------------------------------------------------------------------------------

	private MvcResult unlink(User user, String provider) throws Exception {
		return mockMvc.perform(delete("/api/v1/users/me/linked-identities/" + provider).header("Authorization", bearer(user))).andReturn();
	}

	private static String code(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
	}

	@Test
	void aPasswordAccountCanUnlinkItsOnlyProviderAndIsToldByEmail() throws Exception {
		User user = passwordUser(uniqueEmail());
		userService.link(user, Provider.GOOGLE, sub(), user.getEmail());
		int mailsBefore = mails.total();

		MvcResult result = unlink(user, "google");

		assertThat(result.getResponse().getStatus()).isEqualTo(204);
		assertThat(userService.linkedProviders(user.getId())).isEmpty();
		assertThat(mails.total()).isEqualTo(mailsBefore + 1);
		RecordingEmailService.Sent notice = mails.lastTo(user.getEmail());
		assertThat(notice.kind()).isEqualTo("IDENTITY_UNLINKED");
		assertThat(notice.providers()).containsExactly("Google");
		assertThat(notice.link()).doesNotContain("token");
	}

	@Test
	void aProviderOnlyAccountCannotRemoveItsLastWayToSignIn() throws Exception {
		User user = providerOnlyUser(Provider.GITHUB, "gh-" + UUID.randomUUID());
		int mailsBefore = mails.total();

		MvcResult result = unlink(user, "github");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(result)).isEqualTo("LAST_LOGIN_METHOD");
		assertThat(userService.linkedProviders(user.getId())).containsExactly(Provider.GITHUB);
		assertThat(mails.total()).isEqualTo(mailsBefore); // nothing committed, so no notice
	}

	@Test
	void aProviderOnlyAccountWithTwoProvidersCanRemoveOneButNotTheOther() throws Exception {
		User user = providerOnlyUser(Provider.GOOGLE, sub());
		userService.link(user, Provider.GITHUB, "gh-" + UUID.randomUUID(), user.getEmail());

		assertThat(unlink(user, "google").getResponse().getStatus()).isEqualTo(204);
		MvcResult last = unlink(user, "github");

		assertThat(last.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(last)).isEqualTo("LAST_LOGIN_METHOD");
		assertThat(userService.linkedProviders(user.getId())).containsExactly(Provider.GITHUB);
	}

	@Test
	void unlinkingAProviderThatIsNotLinkedIsNotFound() throws Exception {
		User user = passwordUser(uniqueEmail()); // password only: nothing to unlink

		MvcResult result = unlink(user, "google");

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(code(result)).isEqualTo("IDENTITY_NOT_FOUND");
		assertThat(unlink(user, "facebook").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void twoConcurrentUnlinksCannotLeaveTheAccountWithNoWayToSignIn() throws Exception {
		User user = providerOnlyUser(Provider.GOOGLE, sub());
		userService.link(user, Provider.GITHUB, "gh-" + UUID.randomUUID(), user.getEmail());

		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<String> google = () -> attemptUnlink(go, user, Provider.GOOGLE);
			Callable<String> github = () -> attemptUnlink(go, user, Provider.GITHUB);
			Future<String> first = pool.submit(google);
			Future<String> second = pool.submit(github);
			go.countDown();
			List<String> outcomes = List.of(first.get(), second.get());

			assertThat(outcomes).containsExactlyInAnyOrder("OK", "LAST_LOGIN_METHOD");
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(userService.linkedProviders(user.getId())).hasSize(1);
	}

	private String attemptUnlink(CountDownLatch go, User user, Provider provider) throws Exception {
		go.await();
		try {
			links.unlink(user.getId(), provider);
			return "OK";
		}
		catch (LinkedIdentityRuleException ex) {
			return ex.code();
		}
	}

	// ---- notifications never decide the outcome ----------------------------------------------------------------------

	@Test
	void aMailFailureDoesNotFailTheLinkOrTheUnlink() throws Exception {
		User user = passwordUser(uniqueEmail());
		String sub = sub();
		mails.failIdentityNotices(true);

		MvcResult linked = linkGoogle(user, sub, uniqueEmail());
		assertRedirect(linked, SETTINGS + "?link=ok&provider=google");
		assertThat(userService.findIdentity(Provider.GOOGLE, sub)).isPresent();

		assertThat(unlink(user, "google").getResponse().getStatus()).isEqualTo(204);
		assertThat(userService.linkedProviders(user.getId())).isEmpty();
	}

	// ---- nothing else moved ---------------------------------------------------------------------------------------------

	@Test
	void usersMeKeepsItsShapeAndStillListsProviders() throws Exception {
		User user = providerOnlyUser(Provider.GITHUB, "gh-" + UUID.randomUUID());

		String body = mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(user))).andReturn().getResponse().getContentAsString();

		Map<String, Object> profile = JsonPath.read(body, "$");
		assertThat(profile.keySet()).containsExactlyInAnyOrder("id", "name", "email", "role", "plan", "emailVerified", "linkedProviders");
		assertThat((List<String>) JsonPath.read(body, "$.linkedProviders")).containsExactly("GITHUB");
	}
}
