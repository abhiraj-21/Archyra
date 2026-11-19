package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * {@code POST /users/me/password} through the real filter chain, PostgreSQL and bcrypt: the current password is
 * required, a wrong one counts towards the login lockout, success ends every other session and keeps the caller's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class PasswordChangeIntegrationTest {

	private static final String PASSWORD = "correct horse battery";
	private static final String NEW_PASSWORD = "another long passphrase";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	RecordingEmailService mail;
	@Autowired
	JwtService jwtService;

	@AfterEach
	void mailWorksAgain() {
		mail.failIdentityNotices(false);
	}

	// ---- helpers -------------------------------------------------------------------------------------

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private User verifiedUser(String email) {
		User user = userService.registerIfAbsent(email, "Test User", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	/** A signed-in browser: its access token and its refresh cookie. */
	private record Browser(String accessToken, String refreshToken) {
	}

	private Browser signIn(String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andExpect(status().isOk()).andReturn();
		return new Browser(JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken"),
				result.getResponse().getCookie("refresh_token").getValue());
	}

	private MvcResult changePassword(Browser browser, String current, String next) throws Exception {
		return mockMvc.perform(post("/api/v1/users/me/password").header("Authorization", "Bearer " + browser.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}")).andReturn();
	}

	private int loginStatus(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse().getStatus();
	}

	private int refreshStatus(String refreshToken) throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
		String xsrf = csrf.getResponse().getCookie("XSRF-TOKEN").getValue();
		return mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("XSRF-TOKEN", xsrf), new Cookie("refresh_token", refreshToken))
				.header("X-XSRF-TOKEN", xsrf)).andReturn().getResponse().getStatus();
	}

	// ---- success -------------------------------------------------------------------------------------

	@Test
	void changingThePasswordEndsOtherSessionsKeepsTheCallersAndEmailsTheOwner() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		Browser thisBrowser = signIn(email, PASSWORD);
		Browser otherDevice = signIn(email, PASSWORD);

		MvcResult result = changePassword(thisBrowser, PASSWORD, NEW_PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String body = result.getResponse().getContentAsString();
		assertThat((String) JsonPath.read(body, "$.accessToken")).isNotBlank();
		assertThat((String) JsonPath.read(body, "$.tokenType")).isEqualTo("Bearer");
		assertThat(body).doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD);
		Cookie fresh = result.getResponse().getCookie("refresh_token");
		assertThat(fresh).isNotNull();
		assertThat(fresh.getValue()).isNotBlank().isNotEqualTo(thisBrowser.refreshToken());

		assertThat(refreshStatus(otherDevice.refreshToken())).as("another device's session ended").isEqualTo(401);
		assertThat(refreshStatus(thisBrowser.refreshToken())).as("the replaced cookie is dead too").isEqualTo(401);
		assertThat(refreshStatus(fresh.getValue())).as("the caller stays signed in").isEqualTo(200);

		assertThat(loginStatus(email, PASSWORD)).isEqualTo(401);
		assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(200);

		RecordingEmailService.Sent notice = mail.lastTo(email);
		assertThat(notice.kind()).isEqualTo("PASSWORD_CHANGED");
		assertThat(notice.link()).endsWith("/forgot-password").doesNotContain("token");
	}

	@Test
	void aMailFailureDoesNotFailTheChange() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		Browser browser = signIn(email, PASSWORD);
		mail.failIdentityNotices(true);

		assertThat(changePassword(browser, PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);

		assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(200);
	}

	// ---- refusals ------------------------------------------------------------------------------------

	@Test
	void aWrongCurrentPasswordChangesNothingAndLeavesSessionsAlone() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		Browser browser = signIn(email, PASSWORD);

		MvcResult result = changePassword(browser, "not the password", NEW_PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		String body = result.getResponse().getContentAsString();
		assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("INCORRECT_PASSWORD");
		assertThat(body).doesNotContain("not the password").doesNotContain(NEW_PASSWORD);
		assertThat(loginStatus(email, PASSWORD)).isEqualTo(200);
		assertThat(refreshStatus(browser.refreshToken())).isEqualTo(200);
		assertThat(mail.sentTo(email)).noneMatch(mailItem -> mailItem.kind().equals("PASSWORD_CHANGED"));
	}

	@Test
	void guessingTheCurrentPasswordLocksTheAccountLikeGuessingAtLogin() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		Browser browser = signIn(email, PASSWORD);

		for (int attempt = 0; attempt < 5; attempt++) {
			assertThat(changePassword(browser, "guess number " + attempt, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
		}

		// The account is locked now: even the right password is refused, at login and in the form.
		assertThat(loginStatus(email, PASSWORD)).isEqualTo(401);
		MvcResult locked = changePassword(browser, PASSWORD, NEW_PASSWORD);
		assertThat(locked.getResponse().getStatus()).isEqualTo(400);
		assertThat((String) JsonPath.read(locked.getResponse().getContentAsString(), "$.code")).isEqualTo("INCORRECT_PASSWORD");
	}

	@Test
	void anUnacceptableNewPasswordIsRejectedWithoutChangingAnything() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		Browser browser = signIn(email, PASSWORD);

		for (String next : new String[] { "short", "password123", email, "x".repeat(73), PASSWORD }) {
			MvcResult result = changePassword(browser, PASSWORD, next);
			assertThat(result.getResponse().getStatus()).as(next).isEqualTo(400);
			assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code")).isEqualTo("VALIDATION_ERROR");
			assertThat(result.getResponse().getContentAsString()).doesNotContain(next);
		}

		assertThat(loginStatus(email, PASSWORD)).isEqualTo(200);
		assertThat(refreshStatus(browser.refreshToken())).isEqualTo(200);
	}

	@Test
	void aBlankOrMissingFieldIsAValidationError() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		Browser browser = signIn(email, PASSWORD);

		mockMvc.perform(post("/api/v1/users/me/password").header("Authorization", "Bearer " + browser.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mockMvc.perform(post("/api/v1/users/me/password").header("Authorization", "Bearer " + browser.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void anAccountThatOnlyUsesASocialLoginHasNoPasswordToChange() throws Exception {
		String email = uniqueEmail();
		User social = userService.createSocialUser(email, "Social User");
		String token = jwt(social);

		mockMvc.perform(post("/api/v1/users/me/password").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_PASSWORD_SET"));

		assertThat(userService.findByEmail(email).orElseThrow().hasPassword()).isFalse();
	}

	@Test
	void itNeedsAnAccessToken() throws Exception {
		mockMvc.perform(post("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
						.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	private String jwt(User user) {
		return jwtService.issueFor(user).value();
	}
}
