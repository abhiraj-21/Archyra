package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.ai.index.EmbeddingSpec;
import com.abhiraj.archyra.analysis.Analysis;
import com.abhiraj.archyra.user.User;

/** {@code GET} and {@code POST /analyses/{id}/ai-index}: contract, states and ownership. */
class AiIndexEndpointIntegrationTest extends AiIntegrationSupport {

	@Test
	void aReadyIndexReportsItsCountsModelAndTime() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");

		MvcResult result = getAs(owner, indexPath(id));

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		int chunks = json(result, "$.chunkCount");
		assertThat(chunks).isPositive();
		assertThat((int) json(result, "$.embeddedCount")).isEqualTo(chunks);
		assertThat((String) json(result, "$.embeddingModel")).isEqualTo(EmbeddingSpec.MODEL_NAME);
		assertThat((boolean) json(result, "$.retryable")).isFalse();
		assertThat((Object) json(result, "$.reason")).isNull();
		assertThat(Instant.parse(json(result, "$.indexedAt"))).isBefore(Instant.now().plusSeconds(5));
	}

	@Test
	void anAnalysisFromBeforeAskAiIsNotIndexedAndCannotBeRetried() throws Exception {
		User owner = newUser();
		UUID id = legacyCompletedAnalysis(owner);

		MvcResult get = getAs(owner, indexPath(id));
		assertThat(get.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) json(get, "$.status")).isEqualTo("NOT_INDEXED");
		assertThat((int) json(get, "$.chunkCount")).isZero();
		assertThat((boolean) json(get, "$.retryable")).isFalse();

		MvcResult retry = postAs(owner, indexPath(id));
		assertThat(retry.getResponse().getStatus()).isEqualTo(409);
		assertThat((String) json(retry, "$.code")).isEqualTo("AI_REANALYZE_REQUIRED");
		assertThat(indexRows(id)).isZero(); // asking does not create anything
	}

	@Test
	void anAnalysisThatHasNotCompletedIsConflictForBoth() throws Exception {
		User owner = newUser();
		UUID running = analyses.save(Analysis.queued(SHOP, owner.getId())).getId();

		for (MvcResult result : new MvcResult[] { getAs(owner, indexPath(running)), postAs(owner, indexPath(running)) }) {
			assertThat(result.getResponse().getStatus()).isEqualTo(409);
			assertThat((String) json(result, "$.code")).isEqualTo("ANALYSIS_NOT_COMPLETED");
		}
	}

	@Test
	void everyoneElseGetsNotFoundIncludingAdminsAndUnknownIds() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");
		User stranger = newUser();
		User admin = userService.save(User.administrator("admin-" + UUID.randomUUID() + "@example.com", "Admin",
				passwordEncoder.encode("irrelevant"), Instant.now()));

		for (User caller : new User[] { stranger, admin }) {
			for (MvcResult result : new MvcResult[] { getAs(caller, indexPath(id)), postAs(caller, indexPath(id)) }) {
				assertThat(result.getResponse().getStatus()).isEqualTo(404);
				assertThat((String) json(result, "$.code")).isEqualTo("ANALYSIS_NOT_FOUND");
			}
		}
		assertThat(getAs(owner, indexPath(UUID.randomUUID())).getResponse().getStatus()).isEqualTo(404);
		assertThat(getAs(owner, "/api/v1/analyses/not-a-uuid/ai-index").getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void bothRoutesNeedAToken() throws Exception {
		UUID id = UUID.randomUUID();
		assertThat(mockMvc.perform(get(indexPath(id))).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(post(indexPath(id))).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void retryingAReadyIndexChangesNothing() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");

		MvcResult retry = postAs(owner, indexPath(id));

		assertThat(retry.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) json(retry, "$.status")).isEqualTo("READY");
	}
}
