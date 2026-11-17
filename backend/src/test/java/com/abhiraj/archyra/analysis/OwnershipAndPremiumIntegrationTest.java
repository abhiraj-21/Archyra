package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
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
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;
import com.jayway.jsonpath.JsonPath;

/**
 * Ownership and the premium gate through the real security chain, services and databases: who can see, list and
 * delete which analysis, and which graph requests the free plan may make. Analyses are inserted completed, with no
 * graph, so the endpoints answer from PostgreSQL and an empty graph.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class OwnershipAndPremiumIntegrationTest {

	private static final String URL = "https://github.com/owner/repo";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	AnalysisRepository analyses;
	@Autowired
	UserService userService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JwtService jwtService;
	@Autowired
	JwtEncoder jwtEncoder;
	@MockitoBean
	AnalysisPipeline pipeline; // POST must not start a real clone

	// ---- fixtures ---------------------------------------------------------------------------------------

	private User newUser(Plan plan, Instant planExpiresAt) {
		User user = userService.registerIfAbsent("own-" + UUID.randomUUID() + "@example.com", "Test", passwordEncoder.encode("irrelevant"),
				Instant.now()).orElseThrow();
		user.markEmailVerified();
		user.changePlan(plan, planExpiresAt);
		return userService.save(user);
	}

	private User freeUser() {
		return newUser(Plan.FREE, null);
	}

	private User premiumUser() {
		return newUser(Plan.PREMIUM, Instant.now().plus(Duration.ofDays(30)));
	}

	private User admin() {
		User admin = userService.save(User.administrator("admin-" + UUID.randomUUID() + "@example.com", "Admin",
				passwordEncoder.encode("irrelevant"), Instant.now()));
		return admin;
	}

	private String bearer(User user) {
		return "Bearer " + jwtService.issueFor(user).value();
	}

	private Analysis analysisOf(User owner, AnalysisStatus status) {
		Analysis analysis = Analysis.queued(URL, owner.getId());
		if (status == AnalysisStatus.COMPLETED) {
			analysis.markCompleted();
		}
		else if (status != AnalysisStatus.QUEUED) {
			analysis.advanceTo(status, 40);
		}
		return analyses.save(analysis);
	}

	private Analysis completedAnalysisOf(User owner) {
		return analysisOf(owner, AnalysisStatus.COMPLETED);
	}

	private MvcResult getAs(User caller, String path) throws Exception {
		return mockMvc.perform(get(path).header("Authorization", bearer(caller))).andReturn();
	}

	private static String code(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
	}

	private static String base(Analysis analysis) {
		return "/api/v1/analyses/" + analysis.getId();
	}

	// ---- ownership --------------------------------------------------------------------------------------

	@Test
	void theOwnerReadsEverythingAboutTheirAnalysis() throws Exception {
		User owner = freeUser();
		Analysis analysis = completedAnalysisOf(owner);

		assertThat(getAs(owner, base(analysis)).getResponse().getStatus()).isEqualTo(200);
		// /repository is covered with a real graph by RepositoryEndpointIntegrationTest; this fixture has none
		assertThat(getAs(owner, base(analysis) + "/graph").getResponse().getStatus()).isEqualTo(200);
		assertThat(getAs(owner, base(analysis) + "/files").getResponse().getStatus()).isEqualTo(200);
		// the analysis is theirs, so what is missing inside it is a node, not an analysis
		MvcResult node = getAs(owner, base(analysis) + "/nodes/class:Nope");
		assertThat(node.getResponse().getStatus()).isEqualTo(404);
		assertThat(code(node)).isEqualTo("NODE_NOT_FOUND");
	}

	@Test
	void anotherUserGetsNotFoundForEveryEndpointAndCannotTellItApartFromAnUnknownId() throws Exception {
		User owner = freeUser();
		User stranger = freeUser();
		Analysis analysis = completedAnalysisOf(owner);
		String unknown = "/api/v1/analyses/" + UUID.randomUUID();

		for (String suffix : List.of("", "/repository", "/graph", "/files", "/files/file:src%2FA.java", "/classes/class:A", "/nodes/class:A")) {
			MvcResult foreign = getAs(stranger, base(analysis) + suffix);
			MvcResult missing = getAs(stranger, unknown + suffix);

			assertThat(foreign.getResponse().getStatus()).as(suffix).isEqualTo(404);
			assertThat(code(foreign)).as(suffix).isEqualTo("ANALYSIS_NOT_FOUND");
			assertThat(foreign.getResponse().getContentAsString()).as(suffix).isEqualTo(missing.getResponse().getContentAsString());
		}
	}

	@Test
	void anAdministratorCannotReadAnotherUsersAnalysis() throws Exception {
		User owner = freeUser();
		User admin = admin();
		Analysis analysis = completedAnalysisOf(owner);

		for (String suffix : List.of("", "/repository", "/graph", "/files")) {
			assertThat(getAs(admin, base(analysis) + suffix).getResponse().getStatus()).as(suffix).isEqualTo(404);
		}
		MvcResult list = getAs(admin, "/api/v1/analyses");
		assertThat((List<?>) JsonPath.read(list.getResponse().getContentAsString(), "$.items")).isEmpty();
	}

	@Test
	void anAnalysisFromBeforeAccountsExistedIsVisibleToNobody() throws Exception {
		User someone = freeUser();
		Analysis legacy = Analysis.queued(URL, someone.getId());
		ReflectionTestUtils.setField(legacy, "ownerId", null);
		legacy.markCompleted();
		legacy = analyses.save(legacy);

		assertThat(getAs(someone, base(legacy)).getResponse().getStatus()).isEqualTo(404);
		assertThat(getAs(admin(), base(legacy)).getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void listShowsOnlyTheCallersOwnAnalysesNewestFirstWithPaging() throws Exception {
		User alice = freeUser();
		User bob = freeUser();
		Analysis first = completedAnalysisOf(alice);
		Thread.sleep(5);
		Analysis second = completedAnalysisOf(alice);
		completedAnalysisOf(bob);

		MvcResult page = getAs(alice, "/api/v1/analyses?page=0&size=1");

		String body = page.getResponse().getContentAsString();
		assertThat((Integer) JsonPath.read(body, "$.totalElements")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(body, "$.totalPages")).isEqualTo(2);
		assertThat((String) JsonPath.read(body, "$.items[0].analysisId")).isEqualTo(second.getId().toString());
		String next = getAs(alice, "/api/v1/analyses?page=1&size=1").getResponse().getContentAsString();
		assertThat((String) JsonPath.read(next, "$.items[0].analysisId")).isEqualTo(first.getId().toString());
		assertThat(getAs(alice, "/api/v1/analyses?size=0").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void creatingAnAnalysisMakesTheCallerItsOwnerAndOnlyTheCaller() throws Exception {
		User alice = freeUser();
		User bob = freeUser();

		MvcResult created = mockMvc.perform(post("/api/v1/analyses").header("Authorization", bearer(alice))
				.contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"" + URL + "\"}")).andReturn();

		assertThat(created.getResponse().getStatus()).isEqualTo(202);
		UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.analysisId"));
		assertThat(analyses.findById(id).orElseThrow().getOwnerId()).isEqualTo(alice.getId());
		assertThat(getAs(alice, "/api/v1/analyses/" + id).getResponse().getStatus()).isEqualTo(200);
		assertThat(getAs(bob, "/api/v1/analyses/" + id).getResponse().getStatus()).isEqualTo(404);
		assertThat(mockMvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON)
				.content("{\"repositoryUrl\":\"" + URL + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void onlyTheOwnerCanDeleteAndDeletingRemovesIt() throws Exception {
		User owner = freeUser();
		User stranger = freeUser();
		Analysis analysis = completedAnalysisOf(owner);

		MvcResult foreign = mockMvc.perform(delete(base(analysis)).header("Authorization", bearer(stranger))).andReturn();
		assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
		assertThat(analyses.findById(analysis.getId())).isPresent();

		MvcResult own = mockMvc.perform(delete(base(analysis)).header("Authorization", bearer(owner))).andReturn();
		assertThat(own.getResponse().getStatus()).isEqualTo(204);
		assertThat(analyses.findById(analysis.getId())).isEmpty();
		assertThat(getAs(owner, base(analysis)).getResponse().getStatus()).isEqualTo(404);
		assertThat(mockMvc.perform(delete(base(analysis)).header("Authorization", bearer(owner))).andReturn().getResponse().getStatus())
				.isEqualTo(404);
	}

	@Test
	void aRunningAnalysisCannotBeDeleted() throws Exception {
		User owner = freeUser();
		Analysis running = analysisOf(owner, AnalysisStatus.PARSING);

		MvcResult result = mockMvc.perform(delete(base(running)).header("Authorization", bearer(owner))).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(result)).isEqualTo("ANALYSIS_IN_PROGRESS");
		assertThat(analyses.findById(running.getId())).isPresent();
	}

	// ---- premium ---------------------------------------------------------------------------------------

	private static final List<String> FREE_QUERIES = List.of("", "?level=BEGINNER", "?nodeType=CLASS", "?relationshipType=CALLS",
			"?nodeType=CLASS&relationshipType=CALLS&level=beginner");

	private static final List<String> PREMIUM_QUERIES = List.of("?level=INTERMEDIATE", "?level=EXPERT", "?level=expert", "?level=",
			"?level=NONSENSE", "?rootNodeId=class:A", "?depth=2", "?nodeTypes=CLASS", "?relationshipTypes=CALLS",
			"?direction=OUT", "?maxNodes=10", "?nodeType=CLASS&maxNodes=10", "?anythingNew=1");

	@Test
	void theFreePlanGetsTheBeginnerGraphAndTheV01Filters() throws Exception {
		User free = freeUser();
		Analysis analysis = completedAnalysisOf(free);

		for (String query : FREE_QUERIES) {
			assertThat(getAs(free, base(analysis) + "/graph" + query).getResponse().getStatus()).as(query).isEqualTo(200);
		}
	}

	@Test
	void theFreePlanIsRefusedEveryPremiumGraphRequestWithThePremiumCode() throws Exception {
		User free = freeUser();
		Analysis analysis = completedAnalysisOf(free);

		for (String query : PREMIUM_QUERIES) {
			MvcResult result = getAs(free, base(analysis) + "/graph" + query);

			assertThat(result.getResponse().getStatus()).as(query).isEqualTo(403);
			assertThat(code(result)).as(query).isEqualTo("PREMIUM_FEATURE");
			assertThat(result.getResponse().getContentAsString()).as(query).doesNotContain("nodes", "edges");
		}
		assertThat(JsonPath.<String>read(getAs(free, base(analysis) + "/graph?level=EXPERT").getResponse().getContentAsString(), "$.message"))
				.isEqualTo("Expert graph requires Archyra Premium.");
		assertThat(JsonPath.<String>read(getAs(free, base(analysis) + "/graph?level=INTERMEDIATE").getResponse().getContentAsString(), "$.message"))
				.isEqualTo("Intermediate graph requires Archyra Premium.");
		assertThat(JsonPath.<String>read(getAs(free, base(analysis) + "/graph?depth=2").getResponse().getContentAsString(), "$.message"))
				.isEqualTo("Interactive graph exploration requires Archyra Premium.");
	}

	@Test
	void aPremiumUserGetsEveryRequestAnswered() throws Exception {
		User premium = premiumUser();
		Analysis analysis = completedAnalysisOf(premium);

		for (String query : FREE_QUERIES) {
			assertThat(getAs(premium, base(analysis) + "/graph" + query).getResponse().getStatus()).as(query).isEqualTo(200);
		}
		for (String query : PREMIUM_QUERIES) {
			if (query.startsWith("?nodeTypes") || query.startsWith("?relationshipTypes") || query.startsWith("?direction")) {
				continue; // plain strings the V0.1 endpoint ignores; covered by the free-plan refusal above
			}
			assertThat(getAs(premium, base(analysis) + "/graph" + query).getResponse().getStatus()).as(query).isEqualTo(200);
		}
	}

	@Test
	void anExpiredPremiumPlanIsTreatedAsFree() throws Exception {
		User expired = newUser(Plan.PREMIUM, Instant.now().minusSeconds(5));
		Analysis analysis = completedAnalysisOf(expired);

		MvcResult result = getAs(expired, base(analysis) + "/graph?level=EXPERT");

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(result)).isEqualTo("PREMIUM_FEATURE");
		assertThat(JsonPath.<String>read(getAs(expired, "/api/v1/auth/me").getResponse().getContentAsString(), "$.plan")).isEqualTo("FREE");
	}

	@Test
	void thePlanComesFromTheDatabaseSoAnUpgradeAppliesToTheTokenAlreadyIssued() throws Exception {
		User user = freeUser();
		Analysis analysis = completedAnalysisOf(user);
		String token = bearer(user);
		assertThat(mockMvc.perform(get(base(analysis) + "/graph?level=EXPERT").header("Authorization", token)).andReturn()
				.getResponse().getStatus()).isEqualTo(403);

		user.changePlan(Plan.PREMIUM, null);
		userService.save(user);

		assertThat(mockMvc.perform(get(base(analysis) + "/graph?level=EXPERT").header("Authorization", token)).andReturn()
				.getResponse().getStatus()).isEqualTo(200);
		assertThat(JsonPath.<String>read(mockMvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andReturn()
				.getResponse().getContentAsString(), "$.plan")).isEqualTo("PREMIUM");
	}

	@Test
	void aPlanClaimInTheTokenChangesNothing() throws Exception {
		User free = freeUser();
		Analysis analysis = completedAnalysisOf(free);
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("archyra").subject(free.getId().toString()).issuedAt(now)
				.expiresAt(now.plusSeconds(600)).id(UUID.randomUUID().toString()).claim("roles", List.of("USER"))
				.claim("plan", "PREMIUM").claim("premium", true).build();
		String forged = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

		MvcResult result = mockMvc.perform(get(base(analysis) + "/graph?level=EXPERT").header("Authorization", "Bearer " + forged)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(result)).isEqualTo("PREMIUM_FEATURE");
	}

	@Test
	void aDisabledPremiumUserLosesPremiumImmediately() throws Exception {
		User premium = premiumUser();
		Analysis analysis = completedAnalysisOf(premium);
		premium.setEnabled(false);
		userService.save(premium);

		assertThat(getAs(premium, base(analysis) + "/graph?level=EXPERT").getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void theGateAnswersBeforeLookingUpTheAnalysisSoItRevealsNothingAboutOthers() throws Exception {
		User owner = freeUser();
		User stranger = freeUser();
		Analysis analysis = completedAnalysisOf(owner);
		String unknown = "/api/v1/analyses/" + UUID.randomUUID();

		MvcResult foreign = getAs(stranger, base(analysis) + "/graph?level=EXPERT");
		MvcResult missing = getAs(stranger, unknown + "/graph?level=EXPERT");

		assertThat(foreign.getResponse().getStatus()).isEqualTo(403);
		assertThat(foreign.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
		// without a premium parameter the same requests are plain 404s
		assertThat(getAs(stranger, base(analysis) + "/graph").getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void aPremiumUserStillCannotReadSomeoneElsesAnalysis() throws Exception {
		User owner = freeUser();
		User premium = premiumUser();
		Analysis analysis = completedAnalysisOf(owner);

		MvcResult result = getAs(premium, base(analysis) + "/graph?level=EXPERT");

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(code(result)).isEqualTo("ANALYSIS_NOT_FOUND");
	}
}
