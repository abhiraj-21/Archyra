package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Register, login, refresh, logout and /me through the real filter chain, PostgreSQL and bcrypt. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	RefreshTokenRepository refreshTokens;

	// ---- helpers -------------------------------------------------------------------------------------

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private User verifiedUser(String email) {
		User user = userService.registerIfAbsent(email, "Test User", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	private static String json(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	private MvcResult login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json(email, password)))
				.andReturn();
	}

	private static String refreshCookie(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie("refresh_token");
		return cookie == null ? null : cookie.getValue();
	}

	/** Fetches an XSRF token the way the SPA does: GET /csrf, keep the cookie value and send it back as a header. */
	private String xsrf() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
		return result.getResponse().getCookie("XSRF-TOKEN").getValue();
	}

	private MvcResult refresh(String refreshToken) throws Exception {
		String xsrf = xsrf();
		var request = post("/api/v1/auth/refresh").cookie(new Cookie("XSRF-TOKEN", xsrf)).header("X-XSRF-TOKEN", xsrf);
		if (refreshToken != null) {
			request.cookie(new Cookie("refresh_token", refreshToken), new Cookie("XSRF-TOKEN", xsrf));
		}
		return mockMvc.perform(request).andReturn();
	}

	private static String setCookie(MvcResult result, String name) {
		return result.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(name + "="))
				.findFirst().orElse(null);
	}

	// ---- register ------------------------------------------------------------------------------------

	@Test
	void registerCreatesAnUnverifiedFreeUserAndAnswers202() throws Exception {
		String email = uniqueEmail();

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"  " + email.toUpperCase() + " \",\"password\":\"" + PASSWORD + "\",\"name\":\" Ada \"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.message").value(AuthController.REGISTER_MESSAGE));

		User user = userService.findByEmail(email).orElseThrow();
		assertThat(user.getEmail()).isEqualTo(email);
		assertThat(user.getName()).isEqualTo("Ada");
		assertThat(user.isEmailVerified()).isFalse();
		assertThat(user.getRole().name()).isEqualTo("USER");
		assertThat(user.getPlan().name()).isEqualTo("FREE");
		assertThat(user.getPasswordHash()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
	}

	@Test
	void registeringAnExistingEmailLooksTheSameAndChangesNothing() throws Exception {
		String email = uniqueEmail();
		User existing = verifiedUser(email);
		String body = "{\"email\":\"" + email + "\",\"password\":\"another passphrase\",\"name\":\"Intruder\"}";

		MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted()).andReturn();

		assertThat(result.getResponse().getContentAsString())
				.contains(AuthController.REGISTER_MESSAGE);
		User unchanged = userService.findByEmail(email).orElseThrow();
		assertThat(unchanged.getId()).isEqualTo(existing.getId());
		assertThat(unchanged.getName()).isEqualTo("Test User");
		assertThat(passwordEncoder.matches(PASSWORD, unchanged.getPasswordHash())).isTrue();
	}

	@Test
	void registerRejectsWeakPasswordsWithoutEchoingThem() throws Exception {
		for (String password : new String[] { "short", "password123", "x".repeat(73) }) {
			MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + password + "\",\"name\":\"A\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andReturn();
			assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
		}
	}

	@Test
	void registerRejectsAPasswordThatIsTheEmail() throws Exception {
		String email = uniqueEmail();

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + email + "\",\"name\":\"A\"}"))
				.andExpect(status().isBadRequest());
		assertThat(userService.findByEmail(email)).isEmpty();
	}

	@Test
	void registerValidatesTheBody() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\",\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// ---- login ---------------------------------------------------------------------------------------

	@Test
	void loginReturnsAnAccessTokenAndASecureRefreshCookie() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);

		MvcResult result = login(email, PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String body = result.getResponse().getContentAsString();
		assertThat((String) JsonPath.read(body, "$.tokenType")).isEqualTo("Bearer");
		assertThat((Integer) JsonPath.read(body, "$.expiresIn")).isEqualTo(900);
		assertThat((String) JsonPath.read(body, "$.accessToken")).isNotBlank();
		assertThat(body).doesNotContain("refresh");

		String cookie = setCookie(result, "refresh_token");
		assertThat(cookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=604800");
		assertThat(refreshCookie(result)).hasSize(43);
		assertThat(result.getResponse().getHeaders("Set-Cookie")).hasSize(1); // no session cookie
		assertThat(userService.findByEmail(email).orElseThrow().getLastLoginAt()).isNotNull();
	}

	@Test
	void theAccessTokenOpensMeAndMeDescribesTheCaller() throws Exception {
		String email = uniqueEmail();
		User user = verifiedUser(email);
		String token = JsonPath.read(login(email, PASSWORD).getResponse().getContentAsString(), "$.accessToken");

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(user.getId().toString()))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.name").value("Test User"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.plan").value("FREE"))
				.andExpect(jsonPath("$.emailVerified").value(true))
				.andExpect(jsonPath("$.linkedProviders").isEmpty())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
		mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void wrongPasswordAndUnknownEmailGiveTheSameAnswer() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);

		MvcResult wrong = login(email, "not the password");
		MvcResult unknown = login(uniqueEmail(), "not the password");

		assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
		assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
		assertThat(wrong.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString())
				.contains("INVALID_CREDENTIALS");
		assertThat(wrong.getResponse().getCookies()).isEmpty(); // not even an XSRF cookie on a bearer-style call
	}

	@Test
	void anUnverifiedAccountIsToldSoOnlyAfterTheRightPassword() throws Exception {
		String email = uniqueEmail();
		userService.registerIfAbsent(email, "Unverified", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();

		assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(403);
		assertThat(login(email, PASSWORD).getResponse().getContentAsString()).contains("EMAIL_NOT_VERIFIED");
		MvcResult wrong = login(email, "wrong password");
		assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
		assertThat(wrong.getResponse().getContentAsString()).contains("INVALID_CREDENTIALS");
	}

	@Test
	void aDisabledAccountIsToldSoOnlyAfterTheRightPassword() throws Exception {
		String email = uniqueEmail();
		User user = verifiedUser(email);
		user.setEnabled(false);
		userService.save(user);

		MvcResult right = login(email, PASSWORD);
		assertThat(right.getResponse().getStatus()).isEqualTo(403);
		assertThat(right.getResponse().getContentAsString()).contains("ACCOUNT_DISABLED");
		assertThat(right.getResponse().getCookies()).isEmpty();
		assertThat(login(email, "wrong password").getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void anAccountWithoutAPasswordCannotLogInWithAPassword() throws Exception {
		String email = uniqueEmail();
		userService.save(User.fromSocialLogin(email, "Social"));

		MvcResult result = login(email, "anything at all");

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(result.getResponse().getContentAsString()).contains("INVALID_CREDENTIALS");
	}

	@Test
	void aPasswordThatCouldNeverBeValidIsJustABadPassword() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);

		MvcResult result = login(email, "y".repeat(100));

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void fiveBadPasswordsLockTheAccountWithoutSayingSo() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		MvcResult firstBad = login(email, "bad password 1");
		for (int i = 2; i <= 5; i++) {
			assertThat(login(email, "bad password " + i).getResponse().getStatus()).isEqualTo(401);
		}

		MvcResult lockedButCorrect = login(email, PASSWORD);

		assertThat(lockedButCorrect.getResponse().getStatus()).isEqualTo(401);
		assertThat(lockedButCorrect.getResponse().getContentAsString())
				.isEqualTo(firstBad.getResponse().getContentAsString());
		assertThat(userService.findByEmail(email).orElseThrow().getLockedUntil()).isAfter(Instant.now());
	}

	@Test
	void loginValidatesTheBody() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\",\"password\":\"\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// ---- csrf, refresh, logout ----------------------------------------------------------------------

	@Test
	void theCsrfEndpointSetsAReadableSameSiteCookieAndReturnsTheToken() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
				.andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN")).andReturn();

		String cookie = setCookie(result, "XSRF-TOKEN");
		assertThat(cookie).contains("Path=/").doesNotContain("HttpOnly"); // SameSite is checked over real HTTP in CookieAttributesHttpTest
		assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.token")).isNotBlank();
	}

	@Test
	void refreshWithoutTheCsrfHeaderIs403() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		String refreshToken = refreshCookie(login(email, PASSWORD));

		MvcResult noHeader = mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
				.andReturn();
		MvcResult wrongHeader = mockMvc.perform(post("/api/v1/auth/refresh")
				.cookie(new Cookie("refresh_token", refreshToken), new Cookie("XSRF-TOKEN", "abc")).header("X-XSRF-TOKEN", "def"))
				.andReturn();

		assertThat(noHeader.getResponse().getStatus()).isEqualTo(403);
		assertThat(noHeader.getResponse().getContentAsString()).contains("CSRF_TOKEN_INVALID");
		assertThat(wrongHeader.getResponse().getStatus()).isEqualTo(403);
		// the refresh token was not consumed
		assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void refreshRotatesTheTokenAndIssuesAWorkingAccessToken() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		String first = refreshCookie(login(email, PASSWORD));

		MvcResult result = refresh(first);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String second = refreshCookie(result);
		assertThat(second).isNotNull().isNotEqualTo(first);
		assertThat(setCookie(result, "refresh_token")).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");
		String access = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
		// only hashes are stored
		assertThat(refreshTokens.findByTokenHash(TokenHasher.hash(second))).isPresent();
		assertThat(refreshTokens.findAll()).noneMatch(token -> token.getTokenHash().equals(second));
	}

	@Test
	void reusingARotatedTokenKillsTheWholeFamily() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		String first = refreshCookie(login(email, PASSWORD));
		String second = refreshCookie(refresh(first));

		MvcResult replay = refresh(first); // the thief (or a stale tab) presents the old token

		assertThat(replay.getResponse().getStatus()).isEqualTo(401);
		assertThat(replay.getResponse().getContentAsString()).contains("REFRESH_TOKEN_INVALID");
		assertThat(setCookie(replay, "refresh_token")).contains("Max-Age=0");
		assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401); // the legitimate holder is out too
	}

	@Test
	void refreshWithoutOrWithAnUnknownTokenIs401AndClearsTheCookie() throws Exception {
		MvcResult none = refresh(null);
		MvcResult unknown = refresh(TokenHasher.newToken());

		for (MvcResult result : new MvcResult[] { none, unknown }) {
			assertThat(result.getResponse().getStatus()).isEqualTo(401);
			assertThat(setCookie(result, "refresh_token")).contains("Max-Age=0", "Path=/api/v1/auth");
		}
	}

	@Test
	void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		String token = refreshCookie(login(email, PASSWORD));
		String xsrf = xsrf();

		MvcResult result = mockMvc.perform(post("/api/v1/auth/logout")
				.cookie(new Cookie("refresh_token", token), new Cookie("XSRF-TOKEN", xsrf)).header("X-XSRF-TOKEN", xsrf)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(204);
		assertThat(setCookie(result, "refresh_token")).contains("Max-Age=0");
		assertThat(refresh(token).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void logoutIsAlways204ButNeedsTheCsrfHeader() throws Exception {
		String xsrf = xsrf();

		mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("XSRF-TOKEN", xsrf)).header("X-XSRF-TOKEN", xsrf))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isForbidden());
	}

	@Test
	void disablingTheUserEndsRefreshing() throws Exception {
		String email = uniqueEmail();
		User user = verifiedUser(email);
		String token = refreshCookie(login(email, PASSWORD));
		user.setEnabled(false);
		userService.save(user);

		assertThat(refresh(token).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void aTokenOfADisabledUserNoLongerOpensMe() throws Exception {
		String email = uniqueEmail();
		User user = verifiedUser(email);
		String access = JsonPath.read(login(email, PASSWORD).getResponse().getContentAsString(), "$.accessToken");
		user.setEnabled(false);
		userService.save(user);

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isUnauthorized());
	}
}
