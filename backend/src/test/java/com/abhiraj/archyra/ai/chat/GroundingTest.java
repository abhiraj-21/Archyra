package com.abhiraj.archyra.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ai.retrieval.CodeItem;
import com.abhiraj.archyra.ai.retrieval.ContextItem;
import com.abhiraj.archyra.ai.retrieval.FactItem;
import com.abhiraj.archyra.ai.retrieval.Priority;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

class GroundingTest {

	/** Five items: 1-3 are code, 4 is a fact between two nodes, 5 is code. */
	private static final List<ContextItem> ITEMS = List.of(code("A"), code("B"), code("C"),
			FactItem.relationship("class:X", "X", RelationshipType.DEPENDS_ON, "class:Y", "Y"), code("E"));

	private static CodeItem code(String name) {
		return new CodeItem(Priority.VECTOR, "method:" + name, NodeType.METHOD, name, name + ".java", 1, 2, "text", 0);
	}

	private static Grounding.Outcome ground(String answer) {
		return Grounding.ground(answer, ITEMS, index -> true);
	}

	private static List<String> ids(Grounding.Outcome outcome) {
		return outcome.cited().stream().map(item -> item.nodeIds().getFirst()).toList();
	}

	@Test
	void citationsAreRenumberedByFirstAppearance() {
		Grounding.Outcome outcome = ground("Third [3], then first [1], then third again [3] and fifth [5].");

		assertThat(outcome.text()).isEqualTo("Third [1], then first [2], then third again [1] and fifth [3].");
		assertThat(ids(outcome)).containsExactly("method:C", "method:A", "method:E");
	}

	@Test
	void numbersOutsideTheContextAreDroppedNotGuessed() {
		Grounding.Outcome outcome = ground("Claim [99]. Another [0] one [6] and [2].");

		assertThat(outcome.text()).isEqualTo("Claim. Another one and [1].");
		assertThat(ids(outcome)).containsExactly("method:B");
	}

	@Test
	void noCitationsLeavesTheTextAndCitesNothing() {
		Grounding.Outcome outcome = ground("Just prose, with an array a[ ] and a [link](http://x) and [text].");

		assertThat(outcome.text()).isEqualTo("Just prose, with an array a[ ] and a [link](http://x) and [text].");
		assertThat(outcome.cited()).isEmpty();
	}

	@Test
	void aListOfNumbersBecomesSeparateMarkersAndInvalidOnesInItAreDropped() {
		assertThat(ground("Both [2, 1].").text()).isEqualTo("Both [1][2].");
		assertThat(ground("One valid [2, 77].").text()).isEqualTo("One valid [1].");
		assertThat(ground("Repeated [2,2].").text()).isEqualTo("Repeated [1].");
		assertThat(ground("Adjacent [2][1].").text()).isEqualTo("Adjacent [1][2].");
	}

	@Test
	void markersInCodeAreLeftAlone() {
		String answer = "Use `xs[1]` here [2].\n```java\nint v = a[2] + b[1];\n```\nand then [1].";

		Grounding.Outcome outcome = ground(answer);

		assertThat(outcome.text()).isEqualTo("Use `xs[1]` here [1].\n```java\nint v = a[2] + b[1];\n```\nand then [2].");
		assertThat(ids(outcome)).containsExactly("method:B", "method:A");
	}

	@Test
	void anUnclosedFenceProtectsTheRestOfTheAnswer() {
		assertThat(ground("Before [1].\n```\na[3] never closed").text()).isEqualTo("Before [1].\n```\na[3] never closed");
	}

	@Test
	void aMarkerFollowedByParenthesesIsAMarkdownLink() {
		assertThat(ground("See [1](http://example.com) and [1].").text()).isEqualTo("See [1](http://example.com) and [1].");
	}

	@Test
	void aFactStandsForItsItemAndKeepsBothNodes() {
		Grounding.Outcome outcome = ground("The dependency [4].");

		assertThat(outcome.text()).isEqualTo("The dependency [1].");
		assertThat(outcome.cited().getFirst().nodeIds()).containsExactly("class:X", "class:Y");
	}

	@Test
	void anItemTheCallerDoesNotAcceptIsDroppedLikeAnUnknownNumber() {
		Grounding.Outcome outcome = Grounding.ground("Gone [1], kept [2].", ITEMS, index -> index != 0);

		assertThat(outcome.text()).isEqualTo("Gone, kept [1].");
		assertThat(ids(outcome)).containsExactly("method:B");
	}

	@Test
	void citedIndexesAreInOrderOfFirstAppearanceAndSkipCode() {
		assertThat(Grounding.citedIndexes("[3] x [1] `[2]` [3] [9]", ITEMS.size())).containsExactly(2, 0);
	}

	@Test
	void anEmptyAnswerIsFine() {
		assertThat(ground("").text()).isEmpty();
		assertThat(ground("").cited()).isEmpty();
	}
}
