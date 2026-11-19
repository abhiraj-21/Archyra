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

	@Test
	void theFirstChunksAreKeptTheIndexIsReadyAndTheNoteSaysItCoversPartOfTheRepository() throws Exception {
		User owner = newUser();
		UUID id = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, id, "READY");

		MvcResult index = getAs(owner, indexPath(id));

		assertThat((int) json(index, "$.chunkCount")).isEqualTo(3);
		assertThat((int) json(index, "$.embeddedCount")).isEqualTo(3);
		assertThat((String) json(index, "$.reason")).contains("large repository");
		assertThat((boolean) json(index, "$.retryable")).isFalse();
		assertThat(chunkCount(id)).isEqualTo(3);
	}
}
