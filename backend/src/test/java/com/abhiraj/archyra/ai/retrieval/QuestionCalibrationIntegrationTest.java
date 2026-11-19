package com.abhiraj.archyra.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.abhiraj.archyra.ai.AiIntegrationSupport;
import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.index.ChunkSearch;
import com.abhiraj.archyra.ai.index.EmbeddingService;
import com.abhiraj.archyra.ai.index.FoundChunk;
import com.abhiraj.archyra.user.User;

/**
 * The calibration of "is anything in this repository related to the question?" with the REAL embedding model on the sample
 * repository (a small Java shop). Every on-topic question must be answerable and every off-topic one refused; the measured
 * similarities are written to {@code target/question-calibration.txt} and the numbers are in {@code Architecture.md}. It is a
 * regression test: moving {@code min-similarity}, or changing the model, fails it. One threshold separates the two lists with room on
 * both sides, so there is no second rule.
 */
class QuestionCalibrationIntegrationTest extends AiIntegrationSupport {

	/** Questions about the sample shop: some name a class, some only describe what it does. */
	static final List<String> ON_TOPIC = List.of(
			"What does the OrderController do?",
			"How does placing an order work?",
			"What classes depend on OrderService?",
			"Which class implements OrderService?",
			"How are orders stored?",
			"Explain DefaultOrderService",
			"What does the OrderRepository interface define?",
			"How does the controller call the service?",
			"What is BaseEntity used for?",
			"What is the role of the repository package?",
			"How does an order request become an order?",
			"Show me where orders are saved",
			"What does the web layer of this shop do?",
			"What does `place` do?",
			"What methods does InMemoryOrderRepository have?",
			"How does a request to create an order flow through the code?",
			"What is the status of an order and who changes it?");

	/** Questions that have nothing to do with the repository. */
	static final List<String> OFF_TOPIC = List.of(
			"What's the weather today?",
			"Write me a poem about autumn",
			"How do I center a div in CSS?",
			"Who won the football world cup in 2018?",
			"What is the capital of France?",
			"Translate hello into Spanish",
			"Give me a recipe for pancakes",
			"How do I install Python on Windows?",
			"What is the meaning of life?",
			"Tell me a joke",
			"How does photosynthesis work?",
			"What should I cook for dinner tonight?");

	@Autowired
	RetrievalService retrieval;
	@Autowired
	EmbeddingService embeddings;
	@Autowired
	ChunkSearch chunks;
	@Autowired
	AiProperties properties;

	private double bestSimilarity(UUID analysis, String question) {
		List<FoundChunk> nearest = chunks.nearest(analysis, embeddings.embed(question), properties.retrieval().vectorTopK());
		return nearest.stream().mapToDouble(FoundChunk::similarity).max().orElse(0);
	}

	@Test
	void everyOnTopicQuestionIsAnswerableAndEveryOffTopicOneIsRefused() throws IOException {
		User owner = newUser();
		UUID shop = analyzeCompleted(owner, SHOP);
		awaitIndexStatus(owner, shop, "READY");
		StringBuilder report = new StringBuilder("Question calibration on the sample shop (real all-MiniLM-L6-v2 vectors)\n")
				.append("min-similarity=").append(properties.retrieval().minSimilarity())
				.append("\n\n");
		double lowestOn = 1;
		double highestOff = -1;
		List<String> wrong = new java.util.ArrayList<>();
		report.append("ON-TOPIC (best chunk similarity, whether a node is named, answerable)\n");
		for (String question : ON_TOPIC) {
			double best = bestSimilarity(shop, question);
			RetrievedContext context = retrieval.retrieve(RetrievalRequest.of(shop, question));
			boolean named = context.ranked().stream().anyMatch(item -> item.priority() == Priority.NAME_MATCH);
			lowestOn = Math.min(lowestOn, named ? 1 : best);
			report.append(String.format(Locale.ROOT, "  %.3f %-5s %-6s %s%n", best, named ? "named" : "-", context.usable() ? "ANSWER" : "REFUSE", question));
			if (!context.usable()) {
				wrong.add("on-topic but refused: " + question);
			}
		}
		report.append("\nOFF-TOPIC\n");
		for (String question : OFF_TOPIC) {
			double best = bestSimilarity(shop, question);
			RetrievedContext context = retrieval.retrieve(RetrievalRequest.of(shop, question));
			highestOff = Math.max(highestOff, best);
			report.append(String.format(Locale.ROOT, "  %.3f %-5s %-6s %s%n", best, "-", context.usable() ? "ANSWER" : "REFUSE", question));
			if (context.usable()) {
				wrong.add("off-topic but answered: " + question);
			}
		}
		report.append(String.format(Locale.ROOT, "%nhighest off-topic similarity %.3f, lowest on-topic similarity of a question that names nothing %.3f, min-similarity %.2f%n",
				highestOff, lowestOn, properties.retrieval().minSimilarity()));
		Files.writeString(Path.of("target/question-calibration.txt"), report.toString());
		assertThat(wrong).as("the calibration of min-similarity").isEmpty();
		// and with room on both sides: a new model or a changed fixture must move the threshold, not tip a question over it
		assertThat(highestOff).isLessThan(properties.retrieval().minSimilarity() - 0.04);
		assertThat(lowestOn).isGreaterThan(properties.retrieval().minSimilarity() + 0.04);
	}
}
