package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;

/**
 * The audit trail records the security events with user and IP, and, more importantly, never a password, token or
 * token hash: every line of a full login, refresh, theft and lockout story is checked for them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class AuditLoggingTest {

	private static final String PASSWORD = "correct horse battery";
	private static final String WRONG_PASSWORD = "definitely the wrong one";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Logger auditLogger = (Logger) LoggerFactory.getLogger("security.audit");

	@BeforeEach
	void capture() {
		appender.start();
		auditLogger.addAppender(appender);
	}

	@AfterEach
	void release() {
		auditLogger.detachAppender(appender);
		appender.stop();
	}

	private List<String> lines() {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
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

	@Test
	void theSecurityStoryIsRecordedWithoutAnySecret() throws Exception {
		String email = "audit-" + UUID.randomUUID() + "@example.com";
		User user = userService.registerIfAbsent(email, "Audit", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		userService.save(user);

		for (int i = 0; i < 5; i++) {
			login(email, WRONG_PASSWORD); // the fifth locks the account
		}
		User unlocked = userService.findByEmail(email).orElseThrow();
		unlocked.resetLockout();
		userService.save(unlocked);
		MvcResult ok = login(email, PASSWORD);
		String access = JsonPath.read(ok.getResponse().getContentAsString(), "$.accessToken");
		String firstRefresh = ok.getResponse().getCookie("refresh_token").getValue();
		MvcResult rotated = refresh(firstRefresh);
		String secondRefresh = rotated.getResponse().getCookie("refresh_token").getValue();
		refresh(firstRefresh); // the old token again: theft

		List<String> audit = lines();
		assertThat(audit).anyMatch(line -> line.startsWith("event=LOGIN_FAILED"));
		assertThat(audit).anyMatch(line -> line.startsWith("event=ACCOUNT_LOCKED"));
		assertThat(audit).anyMatch(line -> line.startsWith("event=LOGIN_SUCCESS"));
		assertThat(audit).anyMatch(line -> line.startsWith("event=TOKEN_REFRESHED"));
		assertThat(audit).anyMatch(line -> line.startsWith("event=REFRESH_TOKEN_REUSE"));
		assertThat(audit).filteredOn(line -> line.contains(user.getId().toString())).isNotEmpty()
				.allMatch(line -> line.contains("userId=" + user.getId()) && line.contains("ip="));

		String everything = String.join("\n", audit);
		for (String secret : List.of(PASSWORD, WRONG_PASSWORD, access, firstRefresh, secondRefresh, TokenHasher_hash(firstRefresh),
				TokenHasher_hash(secondRefresh), user.getPasswordHash())) {
			assertThat(everything).doesNotContain(secret);
		}
		assertThat(everything).doesNotContainIgnoringCase("bearer ").doesNotContain("{bcrypt}");
	}

	/** SHA-256 hex of a raw token, as stored; the real hasher is package-private in the auth feature. */
	private static String TokenHasher_hash(String raw) throws Exception {
		byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return java.util.HexFormat.of().formatHex(digest);
	}
}
