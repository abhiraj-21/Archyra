package com.abhiraj.archyra.analysis;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.usage.UsageProperties;
import com.abhiraj.archyra.usage.UsageType;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

/**
 * Helpers for the usage integration tests: users, HTTP requests through the real security chain, and direct reads of
 * the counters. The limits come from the bound configuration, never from a literal.
 */
abstract class UsageFixtures {

	@Autowired
	protected MockMvc mockMvc;
	@Autowired
	protected UserService userService;
	@Autowired
	protected PasswordEncoder passwordEncoder;
	@Autowired
	protected JwtService jwtService;
	@Autowired
	protected AnalysisRepository analyses;
	@Autowired
	protected AnalysisRegistrar registrar;
	@Autowired
	protected UsageProperties usageProperties;
	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected AnalysisRecovery recovery;

	protected int limit() {
		return usageProperties.freeLimit(UsageType.REPOSITORY_ANALYSIS);
	}

	// ---- fixtures ------------------------------------------------------------------------------

	protected User newUser(Plan plan, Instant planExpiresAt) {
		User user = userService.registerIfAbsent("usage-" + UUID.randomUUID() + "@example.com", "Usage Test",
				passwordEncoder.encode("irrelevant"), Instant.now()).orElseThrow();
		user.markEmailVerified();
		user.changePlan(plan, planExpiresAt);
		return userService.save(user);
	}

	protected User freeUser() {
		return newUser(Plan.FREE, null);
	}

	protected User premiumUser() {
		return newUser(Plan.PREMIUM, Instant.now().plus(Duration.ofDays(30)));
	}

	protected String bearer(User user) {
		return "Bearer " + jwtService.issueFor(user).value();
	}

	protected static String freshUrl() {
		return "https://github.com/acme/repo-" + UUID.randomUUID();
	}

	// ---- requests ------------------------------------------------------------------------------

	protected MvcResult create(User user, String url) throws Exception {
		return mockMvc.perform(post("/api/v1/analyses").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"" + url + "\"}")).andReturn();
	}

	/** Creates an analysis and expects 202; returns its id. */
	protected UUID createOk(User user, String url) throws Exception {
		MvcResult result = create(user, url);
		if (result.getResponse().getStatus() != 202) {
			throw new AssertionError("Expected 202 but got " + result.getResponse().getStatus() + ": " + body(result));
		}
		return UUID.fromString(JsonPath.read(body(result), "$.analysisId"));
	}

	protected MvcResult usage(User user) throws Exception {
		return mockMvc.perform(get("/api/v1/usage").header("Authorization", bearer(user))).andReturn();
	}

	protected MvcResult deleteAnalysis(User user, UUID id) throws Exception {
		return mockMvc.perform(delete("/api/v1/analyses/" + id).header("Authorization", bearer(user))).andReturn();
	}

	protected static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	protected static String code(MvcResult result) throws Exception {
		return JsonPath.read(body(result), "$.code");
	}

	// ---- database ------------------------------------------------------------------------------

	/** The stored counter, or 0 when the user has no row yet. */
	protected int used(User user) {
		return jdbc.query("select used_count from user_usage where user_id = ? and usage_type = 'REPOSITORY_ANALYSIS'",
				rs -> rs.next() ? rs.getInt(1) : 0, user.getId());
	}

	protected int usageRows(User user) {
		return jdbc.queryForObject("select count(*) from user_usage where user_id = ?", Integer.class, user.getId());
	}

	protected int analysisRows(User user) {
		return jdbc.queryForObject("select count(*) from analyses where user_id = ?", Integer.class, user.getId());
	}

	protected int chargedAndNotRefunded(User user) {
		return jdbc.queryForObject("select count(*) from analyses where user_id = ? and quota_charged and not quota_refunded",
				Integer.class, user.getId());
	}

	protected void finish(UUID id, AnalysisStatus status) {
		Analysis analysis = analyses.findById(id).orElseThrow();
		if (status == AnalysisStatus.COMPLETED) {
			analysis.markCompleted();
		}
		else {
			analysis.markFailed("test");
		}
		analyses.save(analysis);
	}
}
