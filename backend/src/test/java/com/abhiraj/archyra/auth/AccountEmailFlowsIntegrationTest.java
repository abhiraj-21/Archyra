package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Verification and reset flows through the whole stack; emails are captured by {@link RecordingEmailService}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class AccountEmailFlowsIntegrationTest {

	private static final String PASSWORD = "correct horse battery";
	private static final String NEW_PASSWORD = "a brand new passphrase";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	RecordingEmailService mails;

	private static String uniqueEmail() {
		return "flow-" + UUID.randomUUID() + "@example.com";
	}

	private MvcResult postJson(String path, String body) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	private MvcResult register(String email) throws Exception {
		return postJson("/api/v1/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Flow User\"}");
	}

	private MvcResult login(String email, String password) throws Exception {
		return postJson("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private MvcResult verify(String token) throws Exception {
		return postJson("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}");
	}

	private MvcResult forgot(String email) throws Exception {
		return postJson("/api/v1/auth/forgot-password", "{\"email\":\"" + email + "\"}");
	}

	private MvcResult reset(String token, String newPassword) throws Exception {
		return postJson("/api/v1/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
	}

	private User verifiedUser(String email) {
		User user = userService.registerIfAbsent(email, "Flow User", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	private String xsrf() throws Exception {
		return mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN").getValue();
	}

	private MvcResult refresh(String refreshToken) throws Exception {
		String xsrf = xsrf();
		return mockMvc.perform(post("/api/v1/auth/refresh")
				.cookie(new Cookie("refresh_token", refreshToken), new Cookie("XSRF-TOKEN", xsrf)).header("X-XSRF-TOKEN", xsrf)).andReturn();
	}

	// ---- the whole journey -------------------------------------------------------------------------

	@Test
	void registerVerifyLoginMeRefreshLogoutAndRefreshFails() throws Exception {
		String email = uniqueEmail();
		assertThat(register(email).getResponse().getStatus()).isEqualTo(202);
		assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(403); // not verified yet

		RecordingEmailService.Sent mail = mails.lastTo(email);
		assertThat(mail.kind()).isEqualTo("VERIFY");
		assertThat(mail.link()).startsWith("http://localhost:5173/verify-email?token=");
		assertThat(verify(mail.token()).getResponse().getStatus()).isEqualTo(200);

		MvcResult login = login(email, PASSWORD);
		assertThat(login.getResponse().getStatus()).isEqualTo(200);
		String access = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(true));

		MvcResult refreshed = refresh(login.getResponse().getCookie("refresh_token").getValue());
		assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
		String refreshToken = refreshed.getResponse().getCookie("refresh_token").getValue();

		String xsrf = xsrf();
		mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", refreshToken), new Cookie("XSRF-TOKEN", xsrf))
				.header("X-XSRF-TOKEN", xsrf)).andExpect(status().isNoContent());
		assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
	}

	// ---- registration ---------------------------------------------------------------------------------

	@Test
	void registerAnswersIdenticallyForANewAndAnAlreadyRegisteredAddress() throws Exception {
		String fresh = uniqueEmail();
		String taken = uniqueEmail();
		verifiedUser(taken);

		MvcResult a = register(fresh);
		MvcResult b = register(taken);

		assertThat(a.getResponse().getStatus()).isEqualTo(b.getResponse().getStatus()).isEqualTo(202);
		assertThat(a.getResponse().getContentAsString()).isEqualTo(b.getResponse().getContentAsString());
		assertThat(a.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(b.getResponse().getHeaderNames());
	}

	@Test
	void registeringAnExistingAddressSendsNothing() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		int before = mails.total();

		assertThat(register(email).getResponse().getStatus()).isEqualTo(202);

		assertThat(mails.total()).isEqualTo(before);
		assertThat(mails.sentTo(email)).isEmpty();
	}

	// ---- verify-email ---------------------------------------------------------------------------------

	@Test
	void aVerificationLinkWorksOnce() throws Exception {
		String email = uniqueEmail();
		register(email);
		String token = mails.lastTo(email).token();

		assertThat(verify(token).getResponse().getStatus()).isEqualTo(200);
		assertThat(userService.findByEmail(email).orElseThrow().isEmailVerified()).isTrue();

		MvcResult again = verify(token);
		assertThat(again.getResponse().getStatus()).isEqualTo(400);
		assertThat(again.getResponse().getContentAsString()).contains("INVALID_OR_EXPIRED_TOKEN");
	}

	@Test
	void junkAndResetTokensDoNotVerify() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		forgot(email);
		String resetToken = mails.lastTo(email).token();

		for (String token : new String[] { "nonsense", TokenHasher.newToken(), resetToken }) {
			MvcResult result = verify(token);
			assertThat(result.getResponse().getStatus()).isEqualTo(400);
			assertThat(result.getResponse().getContentAsString()).contains("INVALID_OR_EXPIRED_TOKEN");
		}
		assertThat(reset(resetToken, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200); // still usable for its own purpose
	}

	// ---- resend-verification ----------------------------------------------------------------------------

	@Test
	void resendingIssuesANewLinkAndVoidsTheOldOne() throws Exception {
		String email = uniqueEmail();
		register(email);
		String first = mails.lastTo(email).token();

		MvcResult resend = postJson("/api/v1/auth/resend-verification", "{\"email\":\"" + email + "\"}");

		assertThat(resend.getResponse().getStatus()).isEqualTo(202);
		assertThat(mails.sentTo(email)).hasSize(2);
		String second = mails.lastTo(email).token();
		assertThat(second).isNotEqualTo(first);
		assertThat(verify(first).getResponse().getStatus()).isEqualTo(400);
		assertThat(verify(second).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void resendAnswersIdenticallyForEveryoneAndMailsOnlyUnverifiedAccounts() throws Exception {
		String unverified = uniqueEmail();
		register(unverified);
		String verified = uniqueEmail();
		verifiedUser(verified);
		String unknown = uniqueEmail();
		int before = mails.sentTo(unverified).size();

		MvcResult a = postJson("/api/v1/auth/resend-verification", "{\"email\":\"" + unverified + "\"}");
		MvcResult b = postJson("/api/v1/auth/resend-verification", "{\"email\":\"" + verified + "\"}");
		MvcResult c = postJson("/api/v1/auth/resend-verification", "{\"email\":\"" + unknown + "\"}");

		for (MvcResult result : new MvcResult[] { a, b, c }) {
			assertThat(result.getResponse().getStatus()).isEqualTo(202);
			assertThat(result.getResponse().getContentAsString()).isEqualTo(a.getResponse().getContentAsString())
					.contains(AuthController.GENERIC_EMAIL_MESSAGE);
		}
		assertThat(mails.sentTo(unverified)).hasSize(before + 1);
		assertThat(mails.sentTo(verified)).isEmpty();
		assertThat(mails.sentTo(unknown)).isEmpty();
	}

	@Test
	void aDisabledAccountGetsNoVerificationEmail() throws Exception {
		String email = uniqueEmail();
		register(email);
		User user = userService.findByEmail(email).orElseThrow();
		user.setEnabled(false);
		userService.save(user);
		int before = mails.sentTo(email).size();

		postJson("/api/v1/auth/resend-verification", "{\"email\":\"" + email + "\"}");

		assertThat(mails.sentTo(email)).hasSize(before);
	}

	// ---- forgot / reset ---------------------------------------------------------------------------------

	@Test
	void forgotPasswordAnswersIdenticallyAndMailsOnlyRealEnabledAccounts() throws Exception {
		String known = uniqueEmail();
		verifiedUser(known);
		String unknown = uniqueEmail();
		String disabled = uniqueEmail();
		User off = verifiedUser(disabled);
		off.setEnabled(false);
		userService.save(off);

		MvcResult a = forgot(known);
		MvcResult b = forgot(unknown);
		MvcResult c = forgot(disabled);

		for (MvcResult result : new MvcResult[] { a, b, c }) {
			assertThat(result.getResponse().getStatus()).isEqualTo(202);
			assertThat(result.getResponse().getContentAsString()).isEqualTo(a.getResponse().getContentAsString());
		}
		assertThat(mails.sentTo(known)).hasSize(1);
		assertThat(mails.lastTo(known).link()).startsWith("http://localhost:5173/reset-password?token=");
		assertThat(mails.sentTo(unknown)).isEmpty();
		assertThat(mails.sentTo(disabled)).isEmpty();
	}

	@Test
	void resettingChangesThePasswordEndsEverySessionAndClearsTheLockout() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		MvcResult session = login(email, PASSWORD);
		String oldRefreshToken = session.getResponse().getCookie("refresh_token").getValue();
		for (int i = 0; i < 5; i++) {
			login(email, "wrong password " + i); // locks the account
		}
		assertThat(userService.findByEmail(email).orElseThrow().getLockedUntil()).isNotNull();
		forgot(email);

		MvcResult result = reset(mails.lastTo(email).token(), NEW_PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getCookies()).isEmpty(); // no automatic sign-in
		User user = userService.findByEmail(email).orElseThrow();
		assertThat(user.getLockedUntil()).isNull();
		assertThat(user.getPasswordChangedAt()).isNotNull();
		assertThat(refresh(oldRefreshToken).getResponse().getStatus()).isEqualTo(401);
		assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
		assertThat(login(email, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void aResetLinkWorksOnceAndTheNewestLinkReplacesOlderOnes() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		forgot(email);
		String first = mails.lastTo(email).token();
		forgot(email);
		String second = mails.lastTo(email).token();

		assertThat(reset(first, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
		assertThat(reset(second, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
		MvcResult replay = reset(second, "yet another passphrase");
		assertThat(replay.getResponse().getStatus()).isEqualTo(400);
		assertThat(replay.getResponse().getContentAsString()).contains("INVALID_OR_EXPIRED_TOKEN");
	}

	@Test
	void anUnacceptablePasswordLeavesTheLinkUsable() throws Exception {
		String email = uniqueEmail();
		verifiedUser(email);
		forgot(email);
		String token = mails.lastTo(email).token();

		for (String weak : new String[] { "short", "password123", email }) {
			MvcResult result = reset(token, weak);
			assertThat(result.getResponse().getStatus()).isEqualTo(400);
			assertThat(result.getResponse().getContentAsString()).contains("VALIDATION_ERROR").doesNotContain(weak);
		}
		assertThat(reset(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
		assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void anAccountThatOnlyUsedSocialLoginCanAddAPassword() throws Exception {
		String email = uniqueEmail();
		userService.save(User.fromSocialLogin(email, "Social"));
		assertThat(login(email, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(401);

		forgot(email);
		assertThat(reset(mails.lastTo(email).token(), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);

		assertThat(login(email, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void resetValidatesTheBodyAndDoesNotEchoTheToken() throws Exception {
		MvcResult result = postJson("/api/v1/auth/reset-password", "{\"token\":\"\",\"newPassword\":\"\"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
	}
}
