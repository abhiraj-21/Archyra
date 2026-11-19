package com.abhiraj.archyra.ai.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ai.AiProperties;

/**
 * The real ONNX model. The first run downloads it (about 90 MB) into {@code models/}, which is git-ignored; later runs
 * work offline (see {@code scripts/fetch-embedding-model.sh}).
 */
class EmbeddingServiceTest {

	static final String MODEL_URI = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx";
	static final String TOKENIZER_URI = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json";

	static EmbeddingService service;

	static AiProperties properties(String modelUri) {
		return new AiProperties(null, null, null, null, null, null, null, null, null,
				new AiProperties.Embedding(32, modelUri, TOKENIZER_URI, "models", false), null, null);
	}

	@BeforeAll
	static void loadTheModel() {
		service = new EmbeddingService(properties(MODEL_URI));
		service.verify();
	}

	private static double cosine(float[] a, float[] b) {
		double dot = 0, na = 0, nb = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		return dot / Math.sqrt(na * nb);
	}

	@Test
	void vectorsHaveTheColumnsDimension() {
		assertThat(service.embed("class UserService creates users")).hasSize(EmbeddingSpec.DIMENSION);
	}

	@Test
	void theSameTextGivesTheSameVectorAndBatchesKeepTheirOrder() {
		List<float[]> batch = service.embedAll(List.of("save the order to the database", "send a confirmation email",
				"save the order to the database"));

		assertThat(batch).hasSize(3);
		assertThat(cosine(batch.get(0), batch.get(2))).isGreaterThan(0.999);
		assertThat(cosine(batch.get(0), batch.get(1))).isLessThan(0.95);
	}

	@Test
	void relatedTextIsCloserThanUnrelatedText() {
		float[] question = service.embed("how are orders stored in the database?");
		float[] related = service.embed("OrderRepository saves and finds orders in the database");
		float[] unrelated = service.embed("render the login button in blue with rounded corners");

		assertThat(cosine(question, related)).isGreaterThan(cosine(question, unrelated));
	}

	@Test
	void veryLongInputIsCutInsteadOfFailing() {
		assertThat(service.embed("word ".repeat(5000))).hasSize(EmbeddingSpec.DIMENSION);
	}

	@Test
	void aMissingModelFileGivesAHelpfulMessage() {
		EmbeddingService broken = new EmbeddingService(properties("file:/definitely/not/here/model.onnx"));

		assertThatThrownBy(broken::verify).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("fetch-embedding-model.sh");
	}
}
