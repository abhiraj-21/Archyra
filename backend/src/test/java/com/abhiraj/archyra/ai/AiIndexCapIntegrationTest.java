package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.user.User;

/** A repository with more chunks than allowed is indexed in part, stays READY and says so. */
@TestPropertySource(properties = "archyra.ai.chunk.max-per-analysis=3")
class AiIndexCapIntegrationTest extends AiIntegrationSupport {

	private static final int CODE_CHUNKS = 3;

	@Test
	void theFirstChunksAreKeptTheIndexIsReadyAndTheNoteSaysItCoversPartOfTheRepository() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");

		MvcResult index = getAs(owner, indexPath(id));

		// three chunks of code (the cap) plus the README of the sample repository, which comes after the cap on purpose
		assertThat((int) json(index, "$.chunkCount")).isEqualTo(CODE_CHUNKS + 1);
		assertThat((int) json(index, "$.embeddedCount")).isEqualTo(CODE_CHUNKS + 1);
		assertThat((String) json(index, "$.reason")).contains("large repository");
		assertThat((boolean) json(index, "$.retryable")).isFalse();
		assertThat(chunkCount(id)).isEqualTo(CODE_CHUNKS + 1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE analysis_id = ? AND kind = 'README'", Integer.class, id)).isEqualTo(1);
	}
}
