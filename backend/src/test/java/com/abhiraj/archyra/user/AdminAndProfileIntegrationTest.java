package com.abhiraj.archyra.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
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
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.analysis.AnalysisRepository;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** The admin API and the user's own profile through the real chain and database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class AdminAndProfileIntegrationTest {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JwtService jwtService;
	@Autowired
	AnalysisRepository analyses;

	// ---- fixtures ---------------------------------------------------------------------------------------

	private User user() {
		User user = userService.registerIfAbsent("adm-" + UUID.randomUUID() + "@example.com", "Plain User", passwordEncoder.encode(PASSWORD),
				Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	private User admin() {
		return userService.save(User.administrator("adm-" + UUID.randomUUID() + "@example.com", "An Admin", passwordEncoder.encode(PASSWORD),
				Instant.now()));
	}

	private String bearer(User user) {
		return "Bearer " + jwtService.issueFor(user).value();
	}

	private MvcResult getAs(User caller, String path) throws Exception {
		return mockMvc.perform(get(path).header("Authorization", bearer(caller))).andReturn();
	}

	private MvcResult setStatus(User caller, UUID target, String body) throws Exception {
		return mockMvc.perform(patch("/api/v1/admin/users/" + target + "/status").header("Authorization", bearer(caller))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	private MvcResult login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
	}

	private MvcResult refresh(String token) throws Exception {
		String xsrf = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN").getValue();
		return mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", token), new Cookie("XSRF-TOKEN", xsrf))
				.header("X-XSRF-TOKEN", xsrf)).andReturn();
	}

	private static String code(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
	}

	// ---- who may use the admin API ---------------------------------------------------------------------

	@Test
	void aUserTokenIsRefusedEverywhereUnderAdmin() throws Exception {
		User plain = user();
		User target = user();

		for (MvcResult result : List.of(getAs(plain, "/api/v1/admin/users"), getAs(plain, "/api/v1/admin/users/" + target.getId()),
				setStatus(plain, target.getId(), "{\"enabled\":false}"))) {
			assertThat(result.getResponse().getStatus()).isEqualTo(403);
			assertThat(code(result)).isEqualTo("ACCESS_DENIED");
		}
		assertThat(userService.findById(target.getId()).orElseThrow().isEnabled()).isTrue();
		assertThat(mockMvc.perform(get("/api/v1/admin/users")).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer tampered")).andReturn().getResponse().getStatus())
				.isEqualTo(401);
	}

	@Test
	void anAdminTokenOfAnAccountDisabledSinceIsRefused() throws Exception {
		User first = admin();
		User second = admin();
		String staleToken = bearer(first);
		assertThat(setStatus(second, first.getId(), "{\"enabled\":false}").getResponse().getStatus()).isEqualTo(200);

		MvcResult result = mockMvc.perform(get("/api/v1/admin/users").header("Authorization", staleToken)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403); // the token is still valid, the account no longer is
	}

	// ---- reading -------------------------------------------------------------------------------------------

	@Test
	void listAndDetailShowAccountsWithoutAnySecret() throws Exception {
		User admin = admin();
		User target = user();

		String list = getAs(admin, "/api/v1/admin/users?size=200").getResponse().getContentAsString();
		String detail = getAs(admin, "/api/v1/admin/users/" + target.getId()).getResponse().getContentAsString();

		assertThat((Integer) JsonPath.read(list, "$.size")).isEqualTo(200);
		assertThat((List<String>) JsonPath.read(list, "$.items[*].email")).contains(target.getEmail());
		assertThat((String) JsonPath.read(detail, "$.email")).isEqualTo(target.getEmail());
		assertThat((Boolean) JsonPath.read(detail, "$.enabled")).isTrue();
		assertThat((String) JsonPath.read(detail, "$.plan")).isEqualTo("FREE");
		for (String body : List.of(list, detail)) {
			assertThat(body).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("hash")
					.doesNotContainIgnoringCase("token").doesNotContain("{bcrypt}");
		}
	}

	@Test
	void aMissingOrMalformedUserIsNotFound() throws Exception {
		User admin = admin();

		for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1")) {
			MvcResult result = getAs(admin, "/api/v1/admin/users/" + id);
			assertThat(result.getResponse().getStatus()).as(id).isEqualTo(404);
			assertThat(code(result)).isEqualTo("USER_NOT_FOUND");
		}
		assertThat(getAs(admin, "/api/v1/admin/users?size=0").getResponse().getStatus()).isEqualTo(400);
	}

	// ---- disabling ------------------------------------------------------------------------------------------

	@Test
	void disablingAUserKillsTheirSessionsAndEnablingRestoresLogin() throws Exception {
		User admin = admin();
		User target = user();
		MvcResult session = login(target.getEmail(), PASSWORD);
		String refreshToken = session.getResponse().getCookie("refresh_token").getValue();
		String accessToken = JsonPath.read(session.getResponse().getContentAsString(), "$.accessToken");
		assertThat(mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken)).andReturn().getResponse().getStatus())
				.isEqualTo(200);

		MvcResult disabled = setStatus(admin, target.getId(), "{\"enabled\":false}");

		assertThat(disabled.getResponse().getStatus()).isEqualTo(200);
		assertThat((Boolean) JsonPath.read(disabled.getResponse().getContentAsString(), "$.enabled")).isFalse();
		assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401); // the refresh token was revoked
		MvcResult blocked = login(target.getEmail(), PASSWORD);
		assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(blocked)).isEqualTo("ACCOUNT_DISABLED");
		// the access token has not expired yet, but the account is disabled, so it no longer opens the profile
		assertThat(mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken)).andReturn().getResponse().getStatus())
				.isEqualTo(401);

		assertThat(setStatus(admin, target.getId(), "{\"enabled\":true}").getResponse().getStatus()).isEqualTo(200);
		assertThat(login(target.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void anAdministratorCannotDisableThemselves() throws Exception {
		User admin = admin();

		MvcResult result = setStatus(admin, admin.getId(), "{\"enabled\":false}");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(result)).isEqualTo("CANNOT_DISABLE_SELF");
		assertThat(userService.findById(admin.getId()).orElseThrow().isEnabled()).isTrue();
	}

	@Test
	void theStatusBodyIsValidatedAndCarriesNothingElse() throws Exception {
		User admin = admin();
		User target = user();

		assertThat(setStatus(admin, target.getId(), "{}").getResponse().getStatus()).isEqualTo(400);
		assertThat(setStatus(admin, target.getId(), "{\"enabled\":\"maybe\"}").getResponse().getStatus()).isEqualTo(400);
		// other fields are not part of the request and change nothing
		assertThat(setStatus(admin, target.getId(), "{\"enabled\":true,\"role\":\"ADMIN\",\"plan\":\"PREMIUM\"}").getResponse().getStatus())
				.isEqualTo(200);
		User after = userService.findById(target.getId()).orElseThrow();
		assertThat(after.getRole()).isEqualTo(Role.USER);
		assertThat(after.getPlan()).isEqualTo(Plan.FREE);
	}

	// ---- the user's own profile ---------------------------------------------------------------------------

	@Test
	void profileEndpointsAgreeAndNeedASignedInUser() throws Exception {
		User user = user();

		String users = getAs(user, "/api/v1/users/me").getResponse().getContentAsString();
		String auth = getAs(user, "/api/v1/auth/me").getResponse().getContentAsString();

		assertThat(users).isEqualTo(auth);
		assertThat((String) JsonPath.read(users, "$.email")).isEqualTo(user.getEmail());
		assertThat((String) JsonPath.read(users, "$.role")).isEqualTo("USER");
		assertThat(mockMvc.perform(get("/api/v1/users/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void patchingTheProfileChangesTheNameAndNothingElse() throws Exception {
		User user = user();
		User other = user();

		MvcResult result = mockMvc.perform(patch("/api/v1/users/me").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"  New Name \",\"email\":\"attacker@example.com\",\"role\":\"ADMIN\",\"plan\":\"PREMIUM\","
						+ "\"enabled\":false,\"emailVerified\":false,\"id\":\"" + other.getId() + "\"}")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.name")).isEqualTo("New Name");
		User after = userService.findById(user.getId()).orElseThrow();
		assertThat(after.getName()).isEqualTo("New Name");
		assertThat(after.getEmail()).isEqualTo(user.getEmail());
		assertThat(after.getRole()).isEqualTo(Role.USER);
		assertThat(after.getPlan()).isEqualTo(Plan.FREE);
		assertThat(after.isEnabled()).isTrue();
		assertThat(after.isEmailVerified()).isTrue();
		assertThat(userService.findById(other.getId()).orElseThrow().getName()).isEqualTo("Plain User");
	}

	@Test
	void invalidNamesAreRefused() throws Exception {
		User user = user();

		for (String body : List.of("{}", "{\"name\":\"\"}", "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(101) + "\"}",
				"{\"name\":\"line\\nbreak\"}", "{\"name\":\"bell\\u0007\"}")) {
			MvcResult result = mockMvc.perform(patch("/api/v1/users/me").header("Authorization", bearer(user))
					.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
			assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
			assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
		}
		assertThat(userService.findById(user.getId()).orElseThrow().getName()).isEqualTo("Plain User");
	}

	// ---- GET /admin/stats -----------------------------------------------------------------------------------

	private long stat(User admin, String field) throws Exception {
		MvcResult result = getAs(admin, "/api/v1/admin/stats");
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$." + field)).longValue();
	}

	@Test
	void statsCountRealRowsAndMovePreciselyWithTheData() throws Exception {
		User admin = admin();
		long users = stat(admin, "totalUsers");
		long active = stat(admin, "activeUsers");
		long premium = stat(admin, "premiumUsers");
		long totalAnalyses = stat(admin, "totalAnalyses");

		User recent = user();
		userService.recordSuccessfulLogin(recent.getId(), Instant.now());
		User stale = user();
		userService.recordSuccessfulLogin(stale.getId(), Instant.now().minus(java.time.Duration.ofDays(90)));
		User disabled = user();
		disabled.setEnabled(false);
		userService.recordSuccessfulLogin(disabled.getId(), Instant.now());
		userService.save(disabled);
		User paying = user();
		paying.changePlan(Plan.PREMIUM, Instant.now().plus(java.time.Duration.ofDays(30)));
		userService.save(paying);
		User lapsed = user();
		lapsed.changePlan(Plan.PREMIUM, Instant.now().minus(java.time.Duration.ofDays(1)));
		userService.save(lapsed);
		analyses.save(Analysis.queued("https://github.com/acme/one", recent.getId()));
		analyses.save(Analysis.queued("https://github.com/acme/two", stale.getId()));

		assertThat(stat(admin, "totalUsers")).isEqualTo(users + 5);
		assertThat(stat(admin, "activeUsers")).isEqualTo(active + 1); // not the stale, nor the disabled one
		assertThat(stat(admin, "premiumUsers")).isEqualTo(premium + 1); // not the expired subscription
		assertThat(stat(admin, "totalAnalyses")).isEqualTo(totalAnalyses + 2);
	}

	@Test
	void statsAreForAdministratorsOnly() throws Exception {
		assertThat(getAs(user(), "/api/v1/admin/stats").getResponse().getStatus()).isEqualTo(403);
		assertThat(getAs(user(), "/api/v1/admin/stats").getResponse().getContentAsString()).contains("\"code\"");
		assertThat(mockMvc.perform(get("/api/v1/admin/stats")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}
}
