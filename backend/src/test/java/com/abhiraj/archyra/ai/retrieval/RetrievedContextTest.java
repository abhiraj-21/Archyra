package com.abhiraj.archyra.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.NodeType;

class RetrievedContextTest {

	private static CodeItem code(String name, int chars, Priority priority) {
		return new CodeItem(priority, "method:" + name, NodeType.METHOD, name, "A.java", 1, 2, "x".repeat(chars), 0);
	}

	private static FactItem fact(String text) {
		return new FactItem(text, "class:A", null, null);
	}

	@Test
	void fillsInRankOrderUntilTheBudgetIsUsed() {
		CodeItem first = code("first", 400, Priority.NAME_MATCH);
		CodeItem second = code("second", 400, Priority.VECTOR);
		CodeItem third = code("third", 400, Priority.NEIGHBOR);
		RetrievedContext context = new RetrievedContext(List.of(first, second, third));
		int budget = first.estimatedTokens() + second.estimatedTokens();

		assertThat(context.within(budget)).containsExactly(first, second);
		assertThat(RetrievedContext.tokensOf(context.within(budget))).isLessThanOrEqualTo(budget);
	}

	@Test
	void anItemThatDoesNotFitIsSkippedButSmallerLaterOnesAreStillTaken() {
		CodeItem big = code("big", 4000, Priority.NAME_MATCH);
		FactItem small = fact("A DEPENDS_ON B");
		RetrievedContext context = new RetrievedContext(List.of(big, small));

		assertThat(context.within(big.estimatedTokens() - 1)).containsExactly(small);
	}

	@Test
	void aSmallerBudgetNeverYieldsMoreThanALargerOne() {
		RetrievedContext context = new RetrievedContext(List.of(code("a", 2000, Priority.NAME_MATCH),
				fact("A DEPENDS_ON B"), code("b", 2000, Priority.VECTOR), code("c", 2000, Priority.NEIGHBOR),
				code("d", 2000, Priority.STICKY)));

		int small = RetrievedContext.tokensOf(context.within(900));
		int large = RetrievedContext.tokensOf(context.within(2600));

		assertThat(small).isLessThanOrEqualTo(900);
		assertThat(large).isLessThanOrEqualTo(2600);
		assertThat(small).isLessThan(large);
		assertThat(context.within(0)).isEmpty();
	}

	@Test
	void theEstimateErrsOnTheHighSideOfOneTokenPerFourCharacters() {
		assertThat(ContextItem.estimateTokens("x".repeat(400))).isGreaterThan(100);
	}

	@Test
	void aFactStandsForBothEndsAndASummaryForOne() {
		FactItem edge = FactItem.relationship("class:A", "A", com.abhiraj.archyra.ir.RelationshipType.DEPENDS_ON, "class:B", "B");

		assertThat(edge.text()).isEqualTo("A DEPENDS_ON B");
		assertThat(edge.nodeIds()).containsExactly("class:A", "class:B");
		assertThat(edge.isSummary()).isFalse();
		FactItem summary = FactItem.partialList("class:A", "A");
		assertThat(summary.nodeIds()).containsExactly("class:A");
		assertThat(summary.isSummary()).isTrue();
	}
}
