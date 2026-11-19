package com.abhiraj.archyra.ai.llm;

import java.util.List;
import java.util.Set;

/**
 * The provider ids the code knows something about. Everything else about a provider (URL, model, limits) is
 * configuration. These are the safety rules that must not depend on a property someone can delete:
 * <ul>
 *   <li>{@link #OPENROUTER}: only models whose id ends in {@link #OPENROUTER_FREE_SUFFIX}, so a typo cannot start spending credits.</li>
 *   <li>{@link #OVH}: anonymous mode only. Keyed use is pay-as-you-go (a payment method is required on the project), so no
 *       {@code Authorization} header is ever sent to it.</li>
 *   <li>{@link #COHERE}: trial keys are for non-commercial use; a startup warning says so.</li>
 *   <li>{@link #OPT_IN}: terms or data policies that need a deliberate decision; each needs an explicit {@code enabled=true}.</li>
 * </ul>
 */
final class ProviderRules {

	static final String GEMINI = "gemini";
	static final String OPENROUTER = "openrouter";
	static final String OVH = "ovh";
	static final String COHERE = "cohere";
	static final String OPENROUTER_FREE_SUFFIX = ":free";

	/** Off unless {@code enabled=true} is set explicitly: hosted for prototyping and logging (nvidia-nim), a free router that may use providers that log prompts (kilo), a phone-verified key (gonka). */
	static final Set<String> OPT_IN = Set.of("nvidia-nim", "kilo", "gonka");

	/** Where an enabled opt-in provider that is not listed in {@code order} goes: after the listed ones, in this order. */
	static final List<String> OPT_IN_ORDER = List.of("nvidia-nim", "kilo", "gonka");

	private ProviderRules() {
	}
}
