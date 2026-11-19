package com.abhiraj.archyra.usage;

/**
 * What can be consumed. Every counter, limit and API entry works generically over this enum, so a new category is
 * one value here plus one limit in {@link UsageProperties}. {@code displayName} is what the limit message says.
 */
public enum UsageType {

	REPOSITORY_ANALYSIS("repository analyses"),

	/** One question asked to Ask AI (V1). Reserved before the model is called and returned once if the answer fails. */
	AI_QUERY("AI queries");

	private final String displayName;

	UsageType(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
