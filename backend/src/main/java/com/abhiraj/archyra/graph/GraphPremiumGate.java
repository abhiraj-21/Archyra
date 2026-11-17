package com.abhiraj.archyra.graph;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.security.EntitlementService;
import com.abhiraj.archyra.security.Feature;

import lombok.RequiredArgsConstructor;

/**
 * Decides, from the query parameters alone, whether a {@code GET …/graph} request needs the PREMIUM plan. Because
 * the graph endpoint is shared by every version, the gate is an allow-list for FREE: the V0.1 filters, and the
 * BEGINNER level. Any other parameter, listed or not, is treated as premium-only, so a new advanced parameter is
 * protected from the day it is added and nothing relies on the frontend leaving it out.
 *
 * <p>The check runs before the analysis is looked up, so a free user gets the same 403 whether or not the
 * analysis exists (no probing), and nothing premium is ever computed for them.
 */
@Component
@RequiredArgsConstructor
class GraphPremiumGate {

	/** The V0.1 filters, free for everyone. */
	private static final Set<String> FREE_PARAMETERS = Set.of("nodeType", "relationshipType", "level");

	private final EntitlementService entitlements;

	/**
	 * @param parameterNames every query parameter name of the request
	 * @param level          the value of {@code level}, or null if absent
	 * @throws com.abhiraj.archyra.common.PremiumRequiredException if a premium capability is requested on the free plan
	 */
	void check(Set<String> parameterNames, String level) {
		Feature required = requiredFeature(parameterNames, level);
		if (required != null) {
			entitlements.requirePremium(required);
		}
	}

	/** The most specific premium feature the request asks for, or null if everything it asks for is free. */
	static Feature requiredFeature(Set<String> parameterNames, String level) {
		if (parameterNames.contains("level") && level != null) {
			switch (level.trim().toUpperCase(Locale.ROOT)) {
				case "BEGINNER" -> {
					// free
				}
				case "INTERMEDIATE" -> {
					return Feature.INTERMEDIATE_GRAPH;
				}
				default -> {
					return Feature.EXPERT_GRAPH; // EXPERT, and any value we do not know, get the strictest answer
				}
			}
		}
		else if (parameterNames.contains("level")) {
			return Feature.EXPERT_GRAPH; // "?level=" with no value is not "absent"
		}
		boolean advanced = parameterNames.stream().anyMatch(name -> !FREE_PARAMETERS.contains(name));
		return advanced ? Feature.INTERACTIVE_GRAPH : null;
	}
}
