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
import com.abhiraj.archyra.user.User;

/**
 * Retrieval over a real analysis: real pipeline, real graph in Neo4j, real vectors in pgvector, the real ONNX model. The
 * assertions are about structure that does not depend on exact similarity scores; the scores are written to
 * {@code target/retrieval-checkpoint.txt} so that the behaviour can be read.
 */
class RetrievalIntegrationTest extends AiIntegrationSupport {

	private static final String SERVICE = "interface:com.example.shop.service.OrderService";
	private static final String CONTROLLER = "class:com.example.shop.web.OrderController";
	private static final String BASE_ENTITY = "class:com.example.shop.model.BaseEntity";

	@Autowired
	RetrievalService retrieval;
	@Autowired
	AiProperties properties;

	private User owner;
	private UUID shop;

	private UUID indexedShop() throws Exception {
		if (shop == null) {
			owner = newUser();
			shop = analyzeCompleted(owner, SHOP);
			awaitIndexStatus(owner, shop, "READY");
		}
		return shop;
	}

	private List<ContextItem> ask(String question) throws Exception {
		return retrieval.retrieve(RetrievalRequest.of(indexedShop(), question)).ranked();
	}

	private static List<CodeItem> code(List<ContextItem> items, Priority priority) {
		return items.stream().filter(CodeItem.class::isInstance).map(CodeItem.class::cast)
				.filter(item -> item.priority() == priority).toList();
	}

	private static List<String> factTexts(List<ContextItem> items) {
		return items.stream().filter(FactItem.class::isInstance).map(item -> ((FactItem) item).text()).toList();
	}

	// ---- name lookup ---------------------------------------------------------------------------

	@Test
	void aNamedTypeComesFirstWithItsHeaderAndMethodsAndItsRelationshipsAsFacts() throws Exception {
		List<ContextItem> items = ask("What classes depend on OrderService?");

		List<CodeItem> named = code(items, Priority.NAME_MATCH);
		assertThat(named).isNotEmpty();
		assertThat(named.getFirst().nodeId()).isEqualTo(SERVICE);
		assertThat(named.getFirst().text()).contains("public interface OrderService");
		assertThat(named).extracting(CodeItem::nodeId).contains("method:com.example.shop.service.OrderService#place(OrderRequest)");
		assertThat(items.getFirst()).isEqualTo(named.getFirst()); // name matches rank before everything else
		assertThat(factTexts(items)).contains("OrderController DEPENDS_ON OrderService",
				"DefaultOrderService IMPLEMENTS OrderService");
	}

	@Test
	void aNameFindsItsNodeEvenWhenNothingAboutItIsSimilarToTheQuestion() throws Exception {
		List<ContextItem> items = ask("zzz BaseEntity qqq");

		assertThat(code(items, Priority.NAME_MATCH)).extracting(CodeItem::nodeId).contains(BASE_ENTITY);
		assertThat(factTexts(items)).contains("Order EXTENDS BaseEntity");
	}

	@Test
	void aBacktickedMethodNameFindsEveryMethodOfThatName() throws Exception {
		List<ContextItem> items = ask("What does `place` do?");

		assertThat(code(items, Priority.NAME_MATCH)).extracting(CodeItem::nodeId).contains(
				"method:com.example.shop.service.OrderService#place(OrderRequest)",
				"method:com.example.shop.service.DefaultOrderService#place(OrderRequest)");
	}

	@Test
	void aFileNameBringsTheDeclarationsOfThatFile() throws Exception {
		List<ContextItem> items = ask("what is in OrderController.java?");

		assertThat(code(items, Priority.NAME_MATCH)).extracting(CodeItem::nodeId).contains(CONTROLLER);
	}

	@Test
	void aMethodSeedAlsoExpandsFromItsOwningType() throws Exception {
		List<ContextItem> items = ask("What does `create` do?");

		// OrderController#create is a seed, and so is OrderController: its dependency on OrderService is a fact
		assertThat(factTexts(items)).contains("OrderController DEPENDS_ON OrderService");
	}

	@Test
	void everyItemIsOfAWorkingNodeOfThisAnalysis() throws Exception {
		List<ContextItem> items = ask("How are orders placed and stored?");

		assertThat(items).isNotEmpty();
		for (ContextItem item : items) {
			assertThat(item.nodeIds()).isNotEmpty().allSatisfy(id -> assertThat(id).doesNotStartWith("external:"));
			if (item instanceof CodeItem code) {
				assertThat(code.file()).doesNotContain("src/test/");
				assertThat(code.text()).isNotBlank();
			}
		}
	}

	// ---- vector search -------------------------------------------------------------------------

	@Test
	void aQuestionWithoutNamesIsAnsweredFromTheVectors() throws Exception {
		List<ContextItem> items = ask("How are orders saved and looked up in the repository?");

		List<CodeItem> vector = code(items, Priority.VECTOR);
		assertThat(vector).isNotEmpty();
		assertThat(vector).allSatisfy(item -> assertThat(item.similarity()).isGreaterThanOrEqualTo(properties.retrieval().minSimilarity()));
		assertThat(vector).extracting(CodeItem::nodeId).anyMatch(id -> id.contains("OrderRepository"));
		assertThat(vector).extracting(CodeItem::similarity).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(code(items, Priority.NAME_MATCH)).isEmpty();
	}

	@Test
	void anUnrelatedQuestionFindsLessThanARelatedOne() throws Exception {
		int related = code(ask("How are orders saved and looked up in the repository?"), Priority.VECTOR).size();
		int unrelated = code(ask("What is a good chocolate cake recipe with sugar and eggs?"), Priority.VECTOR).size();

		assertThat(unrelated).isLessThan(related);
	}

	// ---- follow-ups ----------------------------------------------------------------------------

	@Test
	void aShortFollowUpIsResolvedFromThePreviousQuestionAndTheStickySources() throws Exception {
		UUID id = indexedShop();

		List<ContextItem> items = retrieval.retrieve(new RetrievalRequest(id, "explain that class",
				"What does the OrderController do?", List.of(CONTROLLER))).ranked();

		assertThat(code(items, Priority.NAME_MATCH)).isEmpty(); // nothing was named in the follow-up itself
		assertThat(items).filteredOn(CodeItem.class::isInstance).map(item -> ((CodeItem) item).nodeId()).contains(CONTROLLER);
		assertThat(code(items, Priority.STICKY).stream().map(CodeItem::nodeId)).isSubsetOf(CONTROLLER);
		assertThat(factTexts(items)).contains("OrderController DEPENDS_ON OrderService"); // expanded from the sticky seed
	}

	@Test
	void theQueryTextOfALongQuestionIsTheQuestionAloneAndAShortOneIsJoined() {
		UUID id = UUID.randomUUID();

		assertThat(RetrievalService.queryText(new RetrievalRequest(id, "explain that class", "What does the controller do?", List.of())))
				.isEqualTo("What does the controller do? explain that class");
		assertThat(RetrievalService.queryText(new RetrievalRequest(id, "How does the controller place an order today?", "Earlier question", List.of())))
				.isEqualTo("How does the controller place an order today?");
		assertThat(RetrievalService.queryText(RetrievalRequest.of(id, "explain that"))).isEqualTo("explain that");
	}

	// ---- budget and determinism ----------------------------------------------------------------

	@Test
	void theBudgetBoundsTheContextAndASmallerOneGivesLess() throws Exception {
		RetrievedContext context = retrieval.retrieve(RetrievalRequest.of(indexedShop(), "How are orders placed and stored?"));

		int groq = RetrievedContext.tokensOf(context.within(properties.context().groqMaxTokens()));
		int gemini = RetrievedContext.tokensOf(context.within(properties.context().geminiMaxTokens()));
		assertThat(groq).isLessThanOrEqualTo(properties.context().groqMaxTokens());
		assertThat(gemini).isLessThanOrEqualTo(properties.context().geminiMaxTokens());
		assertThat(groq).isLessThanOrEqualTo(gemini);
		int tight = RetrievedContext.tokensOf(context.within(600));
		assertThat(tight).isLessThanOrEqualTo(600).isLessThan(RetrievedContext.tokensOf(context.ranked()));
	}

	@Test
	void theSameQuestionGivesTheSameContextEveryTime() throws Exception {
		RetrievalRequest request = RetrievalRequest.of(indexedShop(), "What classes depend on OrderService and how are orders stored?");

		assertThat(retrieval.retrieve(request)).isEqualTo(retrieval.retrieve(request));
	}

	// ---- test code -----------------------------------------------------------------------------

	@Test
	void testCodeNeverAppearsAsAnItemAFactOrAMatch() throws Exception {
		User tester = newUser();
		UUID id = analyzeCompleted(tester, TESTS_AND_MAIN);
		awaitIndexStatus(tester, id, "READY");

		for (String question : List.of("What depends on GreetingService?", "Tell me about GreetingServiceTest and Samples",
				"How is the greeting tested?")) {
			List<ContextItem> items = retrieval.retrieve(RetrievalRequest.of(id, question)).ranked();

			assertThat(items).as(question).isNotEmpty();
			for (ContextItem item : items) {
				assertThat(item.nodeIds()).as(question).noneMatch(node -> node.contains("GreetingServiceTest") || node.contains("Samples"));
				if (item instanceof FactItem fact) {
					assertThat(fact.text()).doesNotContain("GreetingServiceTest").doesNotContain("Samples");
				}
				if (item instanceof CodeItem code) {
					assertThat(code.file()).doesNotContain("src/test/");
				}
			}
		}
		// the edge from the test to the service exists in the graph and is simply not served
		assertThat(factTexts(retrieval.retrieve(RetrievalRequest.of(id, "What depends on GreetingService?")).ranked()))
				.noneMatch(text -> text.contains("Test"));
	}

	// ---- the checkpoint report -----------------------------------------------------------------

	@Test
	void writeTheCheckpointReport() throws Exception {
		StringBuilder report = new StringBuilder("Retrieval on the sample repository (sample-repo: a small Java shop)\n")
				.append("min-similarity=").append(properties.retrieval().minSimilarity())
				.append(" vector-top-k=").append(properties.retrieval().vectorTopK())
				.append(" neighbors-per-node=").append(properties.retrieval().neighborsPerNode())
				.append(" budgets: gemini=").append(properties.context().geminiMaxTokens())
				.append(" groq=").append(properties.context().groqMaxTokens()).append("\n");
		UUID id = indexedShop();
		record Q(String text, String previous, List<String> sticky) {
		}
		List<Q> questions = List.of(
				new Q("What classes depend on OrderService?", null, List.of()),
				new Q("Explain OrderController", null, List.of()),
				new Q("How does a request to create an order flow through the code?", null, List.of()),
				new Q("How are orders stored?", null, List.of()),
				new Q("What is the role of the repository package?", null, List.of()),
				new Q("How does authentication work?", null, List.of()),
				new Q("What does `place` do?", null, List.of()),
				new Q("explain that class", "Explain OrderController", List.of(CONTROLLER)));
		for (Q question : questions) {
			RetrievedContext context = retrieval.retrieve(new RetrievalRequest(id, question.text(), question.previous(), question.sticky()));
			report.append("\n=== ").append(question.text()).append(question.previous() == null ? "" : "   [follow-up of: " + question.previous() + "]").append('\n');
			int number = 0;
			for (ContextItem item : context.ranked()) {
				number++;
				if (item instanceof CodeItem code) {
					report.append(String.format(Locale.ROOT, "  %2d %-10s %-9s %-62s %s:%d-%d  sim=%.3f  ~%dt%n", number, code.priority(), code.type(),
							code.qualifiedName(), code.file().substring(code.file().lastIndexOf('/') + 1), code.startLine(), code.endLine(),
							code.similarity(), code.estimatedTokens()));
				}
				else {
					FactItem fact = (FactItem) item;
					report.append(String.format(Locale.ROOT, "  %2d %-10s FACT      %s  ~%dt%n", number, Priority.FACT, fact.text(), fact.estimatedTokens()));
				}
			}
			report.append(String.format(Locale.ROOT, "  total %d items, ~%d tokens; fits gemini: %d items / groq: %d items%n", context.ranked().size(),
					RetrievedContext.tokensOf(context.ranked()), context.within(properties.context().geminiMaxTokens()).size(),
					context.within(properties.context().groqMaxTokens()).size()));
		}
		Path file = Path.of("target/retrieval-checkpoint.txt");
		Files.writeString(file, report.toString());
		assertThat(Files.size(file)).isPositive();
	}
}
