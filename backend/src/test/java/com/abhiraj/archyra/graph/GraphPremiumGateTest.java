package com.abhiraj.archyra.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.abhiraj.archyra.security.EntitlementService;
import com.abhiraj.archyra.security.Feature;

class GraphPremiumGateTest {

	@Test
	void noParametersIsFree() {
		assertThat(GraphPremiumGate.requiredFeature(Set.of(), null)).isNull();
	}

	@Test
	void theV01FiltersAreFree() {
		assertThat(GraphPremiumGate.requiredFeature(Set.of("nodeType"), null)).isNull();
		assertThat(GraphPremiumGate.requiredFeature(Set.of("relationshipType"), null)).isNull();
		assertThat(GraphPremiumGate.requiredFeature(Set.of("nodeType", "relationshipType"), null)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "BEGINNER", "beginner", " Beginner " })
	void theBeginnerLevelIsFree(String level) {
		assertThat(GraphPremiumGate.requiredFeature(Set.of("level"), level)).isNull();
		assertThat(GraphPremiumGate.requiredFeature(Set.of("level", "nodeType"), level)).isNull();
	}

	static Stream<Arguments> premiumRequests() {
		return Stream.of(
				Arguments.of(Set.of("level"), "INTERMEDIATE", Feature.INTERMEDIATE_GRAPH),
				Arguments.of(Set.of("level"), "intermediate", Feature.INTERMEDIATE_GRAPH),
				Arguments.of(Set.of("level"), "EXPERT", Feature.EXPERT_GRAPH),
				Arguments.of(Set.of("level"), "expert", Feature.EXPERT_GRAPH),
				Arguments.of(Set.of("level"), "GOD_MODE", Feature.EXPERT_GRAPH),
				Arguments.of(Set.of("level"), "", Feature.EXPERT_GRAPH),
				Arguments.of(Set.of("level", "nodeType"), "EXPERT", Feature.EXPERT_GRAPH),
				Arguments.of(Set.of("level", "depth"), "INTERMEDIATE", Feature.INTERMEDIATE_GRAPH),
				Arguments.of(Set.of("rootNodeId"), null, Feature.INTERACTIVE_GRAPH),
				Arguments.of(Set.of("depth"), null, Feature.INTERACTIVE_GRAPH),
				Arguments.of(Set.of("nodeTypes"), null, Feature.INTERACTIVE_GRAPH),
				Arguments.of(Set.of("relationshipTypes"), null, Feature.INTERACTIVE_GRAPH),
				Arguments.of(Set.of("direction"), null, Feature.INTERACTIVE_GRAPH),
				Arguments.of(Set.of("maxNodes"), null, Feature.INTERACTIVE_GRAPH),
				Arguments.of(Set.of("nodeType", "maxNodes"), null, Feature.INTERACTIVE_GRAPH),
				Arguments.of(Set.of("somethingNobodyListed"), null, Feature.INTERACTIVE_GRAPH),
				Arguments.of(Set.of("Level"), "BEGINNER", Feature.INTERACTIVE_GRAPH)); // names are case-sensitive: unlisted
	}

	@ParameterizedTest
	@MethodSource("premiumRequests")
	void everythingElseNeedsPremium(Set<String> parameters, String level, Feature expected) {
		assertThat(GraphPremiumGate.requiredFeature(parameters, level)).isEqualTo(expected);
	}

	@Test
	void theGateAsksTheEntitlementServiceOnlyWhenSomethingPremiumIsRequested() {
		EntitlementService entitlements = mock(EntitlementService.class);
		GraphPremiumGate gate = new GraphPremiumGate(entitlements);

		gate.check(Set.of("nodeType"), null);
		verify(entitlements, never()).requirePremium(org.mockito.ArgumentMatchers.any());

		gate.check(Set.of("level"), "EXPERT");
		verify(entitlements).requirePremium(Feature.EXPERT_GRAPH);
	}
}
