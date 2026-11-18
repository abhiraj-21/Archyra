package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.mail.RecordingEmailService.Sent;
import com.abhiraj.archyra.user.Provider;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Registering an address that already has an account: the answer is the same generic 202 (so nobody can test which
 * emails have accounts), and what happens next depends on the account, asynchronously and without anything in the
 * response. Real PostgreSQL and bcrypt, the real mail executor, mail recorded instead of sent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class RegisterExistingAccountIntegrationTest {

	private static final String PASSWORD = "correct horse battery";
	private static final String OTHER_PASSWORD = "an entirely different passphrase";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	RecordingEmailService mails;
	@Autowired
	@Qualifier("mailExecutor")
	Executor mailExecutor;

	private final ListAppender<ILoggingEvent> auditLines = new ListAppender<>();

	@BeforeEach
	void captureTheAuditLog() {
		auditLines.start();
		((Logger) LoggerFactory.getLogger("security.audit")).addAppender(auditLines);
	}

	@AfterEach
	void releaseTheAuditLog() {
		((Logger) LoggerFactory.getLogger("security.audit")).detachAppender(auditLines);
	}

	// ---- helpers --------------------------------------------------------------------------------------------

	private static String uniqueEmail() {
		return "dup-" + UUID.randomUUID() + "@example.com";
	}

	private MvcResult register(String email, String name, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"name\":\"" + name + "\"}")).andReturn();
	}

	private MvcResult postJson(String path, String body) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	private int login(String email, String password) throws Exception {
		return postJson("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").getResponse().getStatus();
	}

	private int verify(String token) throws Exception {
		return postJson("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}").getResponse().getStatus();
	}

	/** Waits until the mail executor has finished everything submitted so far: after it, "no mail" is a fact. */
	private void drainMailExecutor() {
		ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) mailExecutor;
		await().atMost(Duration.ofSeconds(15)).until(() -> {
			var executor = pool.getThreadPoolExecutor();
			return executor.getTaskCount() == executor.getCompletedTaskCount();
		});
	}

	private User verifiedPasswordUser(String email) {
		User user = userService.registerIfAbsent(email, "Existing Owner", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	private List<String> audit() {
		return auditLines.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("REGISTER_EXISTING_ACCOUNT")).toList();
	}

	// ---- the response ---------------------------------------------------------------------------------------

	@Test
	void theStatusAndBodyAreIdenticalForNewAndExistingAddressesInEveryAccountState() throws Exception {
		String verified = uniqueEmail();
		verifiedPasswordUser(verified);
		String social = uniqueEmail();
		userService.link(userService.createSocialUser(social, "Social Owner"), Provider.GOOGLE, "g-" + UUID.randomUUID(), social);
		String unverified = uniqueEmail();
		userService.registerIfAbsent(unverified, "Waiting", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		String disabled = uniqueEmail();
		User off = verifiedPasswordUser(disabled);
		off.setEnabled(false);
		userService.save(off);

		MvcResult fresh = register(uniqueEmail(), "New Person", PASSWORD);

		assertThat(fresh.getResponse().getStatus()).isEqualTo(202);
		for (String email : List.of(verified, social, unverified, disabled)) {
			MvcResult existing = register(email, "Someone", OTHER_PASSWORD);
			assertThat(existing.getResponse().getStatus()).isEqualTo(fresh.getResponse().getStatus());
			assertThat(existing.getResponse().getContentAsString()).isEqualTo(fresh.getResponse().getContentAsString());
			assertThat(existing.getResponse().getContentType()).isEqualTo(fresh.getResponse().getContentType());
			assertThat(existing.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(fresh.getResponse().getHeaderNames());
		}
		assertThat(fresh.getResponse().getContentAsString()).doesNotContainIgnoringCase("already").doesNotContainIgnoringCase("registered email");
		drainMailExecutor();
	}

	// ---- verified password account ---------------------------------------------------------------------------

	@Test
	void aVerifiedAccountGetsOneNoticeWithLoginAndResetLinksAndNoToken() throws Exception {
		String email = uniqueEmail();
		User existing = verifiedPasswordUser(email);

		register(email.toUpperCase(), "Intruder", OTHER_PASSWORD); // mixed case matches the lower-cased account
		drainMailExecutor();

		List<Sent> mailsToOwner = mails.sentTo(email);
		assertThat(mailsToOwner).hasSize(1);
		Sent notice = mailsToOwner.get(0);
		assertThat(notice.kind()).isEqualTo("ACCOUNT_EXISTS");
		assertThat(notice.name()).isEqualTo("Existing Owner");
		assertThat(notice.link()).isEqualTo("http://localhost:5173/login");
		assertThat(notice.resetLink()).isEqualTo("http://localhost:5173/forgot-password");
		assertThat(notice.link() + notice.resetLink()).doesNotContain("token");
		assertThat(notice.providers()).isEmpty();

		// no second user, nothing changed
		User unchanged = userService.findByEmail(email).orElseThrow();
		assertThat(unchanged.getId()).isEqualTo(existing.getId());
		assertThat(unchanged.getName()).isEqualTo("Existing Owner");
		assertThat(passwordEncoder.matches(PASSWORD, unchanged.getPasswordHash())).isTrue();
		assertThat(login(email, PASSWORD)).isEqualTo(200);
		assertThat(login(email, OTHER_PASSWORD)).isEqualTo(401);
	}

	@Test
	void aSecondAttemptInsideTheHourSendsNothingMore() throws Exception {
		String email = uniqueEmail();
		verifiedPasswordUser(email);

		register(email, "Intruder", OTHER_PASSWORD);
		drainMailExecutor();
		register(email, "Intruder", OTHER_PASSWORD);
		register(email.toUpperCase(), "Intruder", OTHER_PASSWORD);
		drainMailExecutor();

		assertThat(mails.sentTo(email)).hasSize(1);
		assertThat(audit()).anyMatch(line -> line.contains("case=NOTICE_PASSWORD")).anyMatch(line -> line.contains("case=NOTICE_THROTTLED"));
	}

	// ---- social-only account ---------------------------------------------------------------------------------

	@Test
	void aSocialOnlyAccountGetsANoticeNamingTheProviders() throws Exception {
		String email = uniqueEmail();
		User user = userService.createSocialUser(email, "Social Owner");
		userService.link(user, Provider.GOOGLE, "g-" + UUID.randomUUID(), email);
		userService.link(user, Provider.GITHUB, "gh-" + UUID.randomUUID(), email);

		register(email, "Intruder", OTHER_PASSWORD);
		drainMailExecutor();

		assertThat(mails.sentTo(email)).hasSize(1);
		Sent notice = mails.lastTo(email);
		assertThat(notice.kind()).isEqualTo("ACCOUNT_EXISTS");
		assertThat(notice.providers()).containsExactly("Google", "GitHub");
		assertThat(notice.resetLink()).isEqualTo("http://localhost:5173/forgot-password"); // lets them add a password
		assertThat(userService.findByEmail(email).orElseThrow().hasPassword()).isFalse();
		assertThat(audit()).anyMatch(line -> line.contains("case=NOTICE_SOCIAL_ONLY"));
	}

	// ---- unverified account ----------------------------------------------------------------------------------

	@Test
	void anUnverifiedAccountGetsAFreshLinkAndKeepsItsNameAndPassword() throws Exception {
		String email = uniqueEmail();
		register(email, "First Owner", PASSWORD); // a new address: verification mail goes out synchronously
		String firstToken = mails.lastTo(email).token();
		User before = userService.findByEmail(email).orElseThrow();

		register(email, "Somebody Else", OTHER_PASSWORD);
		await().atMost(Duration.ofSeconds(15)).until(() -> mails.sentTo(email).size() == 2);
		drainMailExecutor();

		List<Sent> sent = mails.sentTo(email);
		assertThat(sent).hasSize(2).allSatisfy(mail -> assertThat(mail.kind()).isEqualTo("VERIFY"));
		String freshToken = sent.get(1).token();
		assertThat(freshToken).isNotEqualTo(firstToken);

		// the stored name and password are NOT replaced: the second submission has proven nothing
		User after = userService.findByEmail(email).orElseThrow();
		assertThat(after.getId()).isEqualTo(before.getId());
		assertThat(after.getName()).isEqualTo("First Owner");
		assertThat(after.getPasswordHash()).isEqualTo(before.getPasswordHash());

		// older links stop working, the fresh one works once
		assertThat(verify(firstToken)).isEqualTo(400);
		assertThat(verify(freshToken)).isEqualTo(200);
		assertThat(verify(freshToken)).isEqualTo(400);
		assertThat(login(email, PASSWORD)).isEqualTo(200);
		assertThat(login(email, OTHER_PASSWORD)).isEqualTo(401);
		assertThat(audit()).anyMatch(line -> line.contains("case=UNVERIFIED_RETRY"));
	}

	// ---- disabled account ------------------------------------------------------------------------------------

	@Test
	void aDisabledAccountGetsNothingAndNothingChanges() throws Exception {
		String email = uniqueEmail();
		User user = verifiedPasswordUser(email);
		user.setEnabled(false);
		userService.save(user);
		String hash = userService.findByEmail(email).orElseThrow().getPasswordHash();
		int totalBefore = mails.total();

		MvcResult result = register(email, "Intruder", OTHER_PASSWORD);
		drainMailExecutor();

		assertThat(result.getResponse().getStatus()).isEqualTo(202);
		assertThat(mails.sentTo(email)).isEmpty();
		assertThat(mails.total()).isEqualTo(totalBefore);
		User unchanged = userService.findByEmail(email).orElseThrow();
		assertThat(unchanged.isEnabled()).isFalse();
		assertThat(unchanged.getPasswordHash()).isEqualTo(hash);
		assertThat(unchanged.getName()).isEqualTo("Existing Owner");
		assertThat(audit()).anyMatch(line -> line.contains("case=DISABLED"));
	}

	// ---- logging ---------------------------------------------------------------------------------------------

	@Test
	void theAuditLinesCarryTheCaseButNoAddressPasswordOrToken() throws Exception {
		String email = uniqueEmail();
		verifiedPasswordUser(email);

		register(email, "Intruder", OTHER_PASSWORD);
		drainMailExecutor();

		assertThat(audit()).isNotEmpty().allSatisfy(line -> assertThat(line).doesNotContain(email).doesNotContain(OTHER_PASSWORD)
				.doesNotContain(PASSWORD).doesNotContainIgnoringCase("token"));
	}
}
