package com.abhiraj.archyra.ai.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

	@Test
	void closingWhileBatchesAreRunningWaitsForThemAndLaterCallsAreRefusedInsteadOfCrashingTheJvm() throws Exception {
		// Freeing the native ONNX session under a running inference crashed the JVM (SIGSEGV) when a Spring context closed during a job.
		EmbeddingService own = new EmbeddingService(properties(MODEL_URI));
		own.verify();
		List<String> batch = java.util.stream.IntStream.range(0, 32).mapToObj(i -> "public Order place(OrderRequest request) number " + i + " ".repeat(5) + "word ".repeat(120)).toList();
		CountDownLatch running = new CountDownLatch(4);
		ExecutorService threads = Executors.newFixedThreadPool(4);
		try {
			List<Future<Integer>> workers = new java.util.ArrayList<>();
			for (int i = 0; i < 4; i++) {
				workers.add(threads.submit(() -> {
					int batches = 0;
					while (true) {
						try {
							own.embedAll(batch);
							batches++;
							running.countDown();
						}
						catch (EmbeddingClosedException closed) {
							return batches;
						}
					}
				}));
			}
			assertThat(running.await(60, TimeUnit.SECONDS)).isTrue(); // every worker is inside the model

			own.destroy();

			for (Future<Integer> worker : workers) {
				assertThat(worker.get(60, TimeUnit.SECONDS)).isPositive(); // each ended by being refused, none by crashing
			}
		}
		finally {
			threads.shutdownNow();
		}
		assertThatThrownBy(() -> own.embed("after")).isInstanceOf(EmbeddingClosedException.class);
		assertThatThrownBy(own::verify).isInstanceOf(EmbeddingClosedException.class);
		own.destroy(); // closing twice is harmless
	}
}
