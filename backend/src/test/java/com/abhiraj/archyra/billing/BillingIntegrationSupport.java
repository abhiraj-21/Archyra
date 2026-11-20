package com.abhiraj.archyra.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.ai.llm.MovableClock;
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.analysis.AnalysisRepository;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.analysis.AnalysisStatus;
import com.abhiraj.archyra.analysis.CreateAnalysisRequest;
import com.abhiraj.archyra.ingestion.GitCloner;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.security.TestCaller;
import com.abhiraj.archyra.usage.MovableClockConfig;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

/**
 * Billing against the real thing: PostgreSQL and Neo4j (Testcontainers), the real pipeline and parser, real HMACs with test
 * secrets, a movable clock, the mail recorder, and a WireMock fake of the Razorpay API (behind {@link RazorpayGateway}, the
 * only external service). Only the GitHub clone is replaced, as in every pipeline test: it copies the sample repository.
 * One fake and one context for every billing class, so they share the containers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class, MovableClockConfig.class })
@ActiveProfiles("test")
abstract class BillingIntegrationSupport {

	static final FakeRazorpay RAZORPAY = new FakeRazorpay().start();
	static final Path SAMPLE = Path.of("src/test/resources/fixtures/sample-repo");

	@DynamicPropertySource
	static void razorpay(DynamicPropertyRegistry registry) {
		registry.add("archyra.billing.razorpay.base-url", RAZORPAY::baseUrl);
		registry.add("archyra.billing.razorpay.key-id", () -> FakeRazorpay.KEY_ID);
		registry.add("archyra.billing.razorpay.key-secret", () -> FakeRazorpay.KEY_SECRET);
		registry.add("archyra.billing.razorpay.webhook-secret", () -> FakeRazorpay.WEBHOOK_SECRET);
		registry.add("archyra.billing.razorpay.plan-monthly", () -> FakeRazorpay.PLAN_MONTHLY);
		registry.add("archyra.billing.razorpay.plan-yearly", () -> FakeRazorpay.PLAN_YEARLY);
	}

	@Autowired
	protected MockMvc mockMvc;
	@Autowired
	protected UserService userService;
	@Autowired
	protected PasswordEncoder passwordEncoder;
	@Autowired
	protected JwtService jwtService;
	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected MovableClock clock;
	@Autowired
	protected RecordingEmailService mail;
	@Autowired
	protected BillingService billing;
	@Autowired
	protected SubscriptionService subscriptions;
	@Autowired
	protected BillingWebhooks webhooks;
	@Autowired
	protected AnalysisService analysisService;
	@Autowired
	protected AnalysisRepository analyses;

	@MockitoBean
	protected GitCloner gitCloner;

	/** Friday 20 November 2026, 15:30 in Kolkata. */
	static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

	@BeforeEach
	void freshFakeAndTime() {
		RAZORPAY.reset();
		clock.setTo(NOW);
		doAnswer(invocation -> {
			FileSystemUtils.copyRecursively(SAMPLE, invocation.<Path>getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	// ---- users ---------------------------------------------------------------------------------

	protected User newUser() {
		User user = userService.registerIfAbsent("billing-" + UUID.randomUUID() + "@example.com", "Billing Test",
				passwordEncoder.encode("irrelevant"), clock.instant()).orElseThrow();
		user.markEmailVerified();
		return userService.save(user);
	}

	protected User premiumUntil(Instant end) {
		User user = newUser();
		user.changePlan(Plan.PREMIUM, end);
		return userService.save(user);
	}

	protected User reload(User user) {
		return userService.findById(user.getId()).orElseThrow();
	}

	protected Instant expiry(User user) {
		return reload(user).getPlanExpiresAt();
	}

	protected String bearer(User user) {
		return "Bearer " + jwtService.issueFor(user).value();
	}

	// ---- HTTP ----------------------------------------------------------------------------------

	protected MvcResult getAs(User user, String path) throws Exception {
		return mockMvc.perform(get(path).header("Authorization", bearer(user))).andReturn();
	}

	protected MvcResult postAs(User user, String path, String body) throws Exception {
		return mockMvc.perform(post(path).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	protected static <T> T json(MvcResult result, String path) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), path);
	}

	protected static String code(MvcResult result) throws Exception {
		return json(result, "$.code");
	}

	/** {@code POST /orders} for a period; returns Razorpay's order id. */
	protected String createOrder(User user, String period) throws Exception {
		MvcResult created = postAs(user, "/api/v1/billing/orders", "{\"period\":\"" + period + "\"}");
		assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(200);
		return json(created, "$.orderId");
	}

	/** What the browser sends after a successful checkout: a payment id and the signature Razorpay computed. */
	protected MvcResult verifyOrder(User user, String orderId, String paymentId) throws Exception {
		return postAs(user, "/api/v1/billing/orders/verify", """
				{"razorpayOrderId":"%s","razorpayPaymentId":"%s","razorpaySignature":"%s"}"""
				.formatted(orderId, paymentId, FakeRazorpay.orderSignature(orderId, paymentId)));
	}

	/** One full one-time payment through checkout and verify; returns the Razorpay payment id. */
	protected String pay(User user, String period) throws Exception {
		String orderId = createOrder(user, period);
		String paymentId = FakeRazorpay.paymentId();
		MvcResult verified = verifyOrder(user, orderId, paymentId);
		assertThat(verified.getResponse().getStatus()).as(verified.getResponse().getContentAsString()).isEqualTo(200);
		return paymentId;
	}

	/** A signed webhook delivery, as Razorpay sends it. */
	protected MvcResult webhook(String eventId, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/billing/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Razorpay-Signature", FakeRazorpay.webhookSignature(body)).header("X-Razorpay-Event-Id", eventId)).andReturn();
	}

	protected int paymentRows(User user, String status) {
		return jdbc.queryForObject("SELECT count(*) FROM payments WHERE user_id = ? AND status = ?", Integer.class, user.getId(), status);
	}

	// ---- analyses ------------------------------------------------------------------------------

	/** A real analysis of the sample repository under a unique URL, waited for until it has finished. */
	protected UUID analyzeCompleted(User user) {
		String url = "https://github.com/example/repo-" + FakeRazorpay.random().toLowerCase();
		UUID id = UUID.fromString(TestCaller.runAs(user.getId(),
				() -> analysisService.createAnalysis(new CreateAnalysisRequest(url))).analysisId());
		await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(100))
				.until(() -> analyses.findById(id).map(Analysis::isFinished).orElse(false));
		assertThat(analyses.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		return id;
	}
}
