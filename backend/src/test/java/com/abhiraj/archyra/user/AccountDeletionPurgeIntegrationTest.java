package com.abhiraj.archyra.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.ai.ChatIntegrationSupport;
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.analysis.AnalysisPurgeService;
import com.abhiraj.archyra.usage.UsageLedgerService;

/**
 * The deletion with real data: the real pipeline, a Neo4j graph, Ask AI chunks and conversations. Everything of the
 * account goes, and no analysis ever gives its credit back, so the ledger holds exactly what was consumed.
 */
@TestPropertySource(properties = { "archyra.usage.free-tier.repository-analysis-limit=2", "archyra.usage.free-tier.ai-query-limit=3" })
class AccountDeletionPurgeIntegrationTest extends ChatIntegrationSupport {

	private static final String PASSWORD = "irrelevant"; // what AiIntegrationSupport.newUser hashes

	@Autowired
	AnalysisPurgeService purge;
	@Autowired
	UsageLedgerService ledger;
	@Autowired
	TransactionTemplate transactions;

	private long graphNodes(UUID analysisId) {
		return neo4j.query("MATCH (n {analysisId: $id}) RETURN count(n) AS c").bind(analysisId.toString()).to("id")
				.fetchAs(Long.class).mappedBy((type, record) -> record.get("c").asLong()).one().orElse(0L);
	}

	private int count(String table, String column, Object value) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
	}

	private MvcResult deleteAccount(User user) throws Exception {
		return mockMvc.perform(post("/api/v1/users/me/delete").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}")).andReturn();
	}

	@Test
	void everythingOfTheAccountGoesAndTheConsumptionStaysEvenForTheSameEmail() throws Exception {
		User user = newUser();
		Ready shop = readyShop(user);
		UUID second = analyzeCompleted(user, TESTS_AND_MAIN);
		for (int i = 1; i <= 3; i++) {
			ask(user, shop.analysisId(), "Question number " + i, null);
		}
		assertThat(used(user, "REPOSITORY_ANALYSIS")).isEqualTo(2);
		assertThat(used(user, "AI_QUERY")).isEqualTo(3);
		assertThat(graphNodes(shop.analysisId())).isPositive();
		assertThat(count("code_chunks", "analysis_id", shop.analysisId())).isPositive();
		assertThat(conversationRows(shop.analysisId())).isPositive();

		MvcResult deleted = deleteAccount(user);

		assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
		for (UUID id : List.of(shop.analysisId(), second)) {
			assertThat(analyses.findById(id)).isEmpty();
			assertThat(graphNodes(id)).isZero();
			assertThat(count("analysis_metadata", "analysis_id", id)).isZero();
			assertThat(count("code_chunks", "analysis_id", id)).isZero();
			assertThat(count("ai_index", "analysis_id", id)).isZero();
			assertThat(conversationRows(id)).isZero();
			assertThat(storedMessages(id)).isZero();
		}
		assertThat(count("pending_graph_purges", "1", 1)).isZero();
		// no credit came back: the ledger equals what was used before the deletion
		String key = ledger.emailKey(user.getEmail());
		assertThat(jdbc.queryForObject("SELECT used_count FROM usage_ledger WHERE identity_key = ? AND usage_type = 'REPOSITORY_ANALYSIS'", Integer.class, key)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT used_count FROM usage_ledger WHERE identity_key = ? AND usage_type = 'AI_QUERY'", Integer.class, key)).isEqualTo(3);

		User returned = userService.registerIfAbsent(user.getEmail(), "Back", passwordEncoder.encode(PASSWORD), Instant.now()).orElseThrow();
		assertThat(used(returned, "REPOSITORY_ANALYSIS")).isEqualTo(2);
		assertThat(used(returned, "AI_QUERY")).isEqualTo(3);
		MvcResult refused = mockMvc.perform(post("/api/v1/analyses").header("Authorization", bearer(returned))
				.contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"" + SHOP + "\"}")).andReturn();
		assertThat(refused.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(refused)).isEqualTo("USAGE_LIMIT_EXCEEDED");
	}

	@Test
	void purgingAnalysesNeverRefundsACreditButDeletingOneNormallyDoes() throws Exception {
		User user = newUser();
		UUID kept = analyzeCompleted(user, SHOP);
		UUID purged = analyzeCompleted(user, TESTS_AND_MAIN);
		assertThat(used(user, "REPOSITORY_ANALYSIS")).isEqualTo(2);

		// the purge path, inside a transaction that is rolled back so the data stays
		int[] insideTheTransaction = new int[2];
		transactions.executeWithoutResult(status -> {
			insideTheTransaction[0] = jdbc.queryForObject("SELECT used_count FROM user_usage WHERE user_id = ? AND usage_type = 'REPOSITORY_ANALYSIS'", Integer.class, user.getId());
			purge.purgeAllOf(user.getId());
			insideTheTransaction[1] = jdbc.queryForObject("SELECT used_count FROM user_usage WHERE user_id = ? AND usage_type = 'REPOSITORY_ANALYSIS'", Integer.class, user.getId());
			status.setRollbackOnly();
		});
		assertThat(insideTheTransaction).containsExactly(2, 2);
		assertThat(analyses.findById(kept)).isPresent();
		assertThat(analyses.findById(purged)).isPresent();
		assertThat(count("pending_graph_purges", "1", 1)).isZero(); // rolled back with everything else
		assertThat(graphNodes(purged)).isPositive();

		// control: the ordinary delete gives the credit back
		assertThat(deleteAs(user, "/api/v1/analyses/" + purged).getResponse().getStatus()).isEqualTo(204);
		assertThat(used(user, "REPOSITORY_ANALYSIS")).isEqualTo(1);
	}

	@Test
	void aGraphThatCouldNotBeDroppedAfterTheCommitIsRetriedByTheSweep() throws Exception {
		User user = newUser();
		UUID id = analyzeCompleted(user, SHOP);
		assertThat(graphNodes(id)).isPositive();

		// the rows commit but the process dies before Neo4j is asked: no dropGraphs call
		List<UUID> ids = transactions.execute(status -> purge.purgeAllOf(user.getId()));

		assertThat(ids).containsExactly(id);
		assertThat(analyses.findById(id)).isEmpty();
		assertThat(count("pending_graph_purges", "analysis_id", id)).isEqualTo(1);
		assertThat(graphNodes(id)).isPositive();

		purge.sweep();

		assertThat(graphNodes(id)).isZero();
		assertThat(count("pending_graph_purges", "analysis_id", id)).isZero();
		purge.sweep(); // idempotent
	}

	@Test
	void anAnalysisThatIsStillRunningStopsTheDeletionAndNothingIsRecorded() throws Exception {
		User user = newUser();
		Analysis running = analyses.save(Analysis.queued(SHOP, user.getId()));

		MvcResult refused = deleteAccount(user);

		assertThat(refused.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(refused)).isEqualTo("ANALYSIS_IN_PROGRESS");
		assertThat(userService.findById(user.getId())).isPresent();
		assertThat(analyses.findById(running.getId())).isPresent();
		assertThat(count("usage_ledger", "identity_key", ledger.emailKey(user.getEmail()))).isZero();
	}
}
