package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.analysis.AnalysisRepository;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.analysis.AnalysisStatus;
import com.abhiraj.archyra.analysis.CreateAnalysisRequest;
import com.abhiraj.archyra.common.AsyncConfig;
import com.abhiraj.archyra.ingestion.GitCloner;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.security.TestCaller;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

/**
 * Ask AI tests against the real thing: the real pipeline and parser, PostgreSQL with pgvector and Neo4j in
 * Testcontainers, and the real ONNX embedding model (downloaded on the first run into {@code models/}). Only the GitHub
 * clone is replaced: it copies a fixture. The repository URL picks the fixture, see {@link #cloneFixtures}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
// Each Spring context here holds a PostgreSQL and a Neo4j container plus an embedding model. The suite already keeps many
// contexts alive, and a few more exhausted the memory of a 8 GB Docker VM (the kernel killed Neo4j containers), so these
// classes give their context back as soon as they are done.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AiIntegrationSupport {

	protected static final Path FIXTURES = Path.of("src/test/resources/fixtures");
	protected static final String SHOP = "https://github.com/example/sample-shop";
	protected static final String TESTS_AND_MAIN = "https://github.com/example/tests-and-main";
	protected static final String ONLY_TESTS = "https://github.com/example/only-tests";

	@Autowired
	protected MockMvc mockMvc;
	@Autowired
	protected AnalysisService analysisService;
	@Autowired
	protected AnalysisRepository analyses;
	@Autowired
	protected UserService userService;
	@Autowired
	protected PasswordEncoder passwordEncoder;
	@Autowired
	protected JwtService jwtService;
	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected ApplicationEventPublisher events;
	@Autowired
	@Qualifier(AsyncConfig.AI_INDEX_EXECUTOR)
	protected Executor aiIndexExecutor;

	@MockitoBean
	protected GitCloner gitCloner;

	@BeforeEach
	void cloneFixtures() {
		doAnswer(invocation -> {
			cloneFixtures(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	/** The fixture a repository URL stands for. */
	private static void cloneFixtures(String url, Path target) throws IOException {
		switch (url) {
			case SHOP -> FileSystemUtils.copyRecursively(FIXTURES.resolve("sample-repo"), target);
			case TESTS_AND_MAIN -> FileSystemUtils.copyRecursively(FIXTURES.resolve("tests-and-main"), target);
			case ONLY_TESTS -> {
				Files.createDirectories(target.resolve("tests"));
				Files.writeString(target.resolve("tests/test_it.py"), "def test_it():\n    assert True\n");
			}
			default -> FileSystemUtils.copyRecursively(FIXTURES.resolve("sample-repo"), target);
		}
	}

	// ---- users and analyses --------------------------------------------------------------------

	protected User newUser() {
		return newUser(Plan.FREE);
	}

	protected User newUser(Plan plan) {
		User user = userService.registerIfAbsent("ai-" + UUID.randomUUID() + "@example.com", "Test",
				passwordEncoder.encode("irrelevant"), Instant.now()).orElseThrow();
		user.markEmailVerified();
		user.changePlan(plan, plan == Plan.PREMIUM ? Instant.now().plus(Duration.ofDays(30)) : null);
		return userService.save(user);
	}

	protected String bearer(User user) {
		return "Bearer " + jwtService.issueFor(user).value();
	}

	/** Starts an analysis of {@code url} as {@code user} with the real pipeline and waits until it has finished. */
	protected UUID analyze(User user, String url) {
		UUID id = UUID.fromString(TestCaller.runAs(user.getId(),
				() -> analysisService.createAnalysis(new CreateAnalysisRequest(url))).analysisId());
		await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(100))
				.until(() -> analyses.findById(id).map(Analysis::isFinished).orElse(false));
		return id;
	}

	protected UUID analyzeCompleted(User user, String url) {
		UUID id = analyze(user, url);
		assertThat(analyses.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		return id;
	}

	/** A completed analysis row with no graph and no index, like one made before Ask AI existed. */
	protected UUID legacyCompletedAnalysis(User owner) {
		Analysis analysis = Analysis.queued(SHOP, owner.getId());
		analysis.markCompleted();
		return analyses.save(analysis).getId();
	}

	// ---- HTTP ----------------------------------------------------------------------------------

	protected MvcResult getAs(User user, String path) throws Exception {
		return mockMvc.perform(get(path).header("Authorization", bearer(user))).andReturn();
	}

	protected MvcResult postAs(User user, String path) throws Exception {
		return mockMvc.perform(post(path).header("Authorization", bearer(user))).andReturn();
	}

	protected MvcResult deleteAs(User user, String path) throws Exception {
		return mockMvc.perform(delete(path).header("Authorization", bearer(user))).andReturn();
	}

	protected static String indexPath(UUID analysisId) {
		return "/api/v1/analyses/" + analysisId + "/ai-index";
	}

	protected static <T> T json(MvcResult result, String path) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), path);
	}

	protected String indexStatus(User user, UUID analysisId) throws Exception {
		MvcResult result = getAs(user, indexPath(analysisId));
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return json(result, "$.status");
	}

	protected void awaitIndexStatus(User user, UUID analysisId, String status) {
		await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(200)).untilAsserted(
				() -> assertThat(indexStatus(user, analysisId)).isEqualTo(status));
	}

	// ---- the embedding executor ----------------------------------------------------------------

	/** Waits until no embedding job runs or waits, so a test starts from an idle executor. */
	protected void awaitIdleEmbeddingExecutor() {
		ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) aiIndexExecutor;
		await().atMost(Duration.ofSeconds(120)).until(() -> pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
	}

	/**
	 * Occupies the single embedding worker until the returned latch is released, so embedding jobs queue up behind it.
	 * Always release it (in a finally block).
	 */
	protected CountDownLatch blockEmbeddingWorker() throws InterruptedException {
		awaitIdleEmbeddingExecutor();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		aiIndexExecutor.execute(() -> {
			started.countDown();
			try {
				release.await(120, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
		return release;
	}

	// ---- database ------------------------------------------------------------------------------

	protected int chunkCount(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ?", Integer.class, analysisId);
	}

	protected int indexRows(UUID analysisId) {
		return jdbc.queryForObject("SELECT count(*) FROM ai_index WHERE analysis_id = ?", Integer.class, analysisId);
	}
}
