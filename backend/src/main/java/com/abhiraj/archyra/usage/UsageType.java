package com.abhiraj.archyra.usage;

/**
 * What can be consumed. Every counter, limit and API entry works generically over this enum, so a new category is
 * one value here plus one limit in {@link UsageProperties}. {@code displayName} is what the limit message says; {@code weekly}
 * says whether the allowance resets every Monday instead of lasting for life.
 */
public enum UsageType {

	REPOSITORY_ANALYSIS("repository analyses", false),

	/**
	 * One question asked to Ask AI (V1). Reserved before the model is called and returned once if the answer fails. The
	 * allowance is weekly: it starts again every Monday 00:00 in {@code archyra.usage.reset-zone}.
	 */
	AI_QUERY("AI queries", true);

	private final String displayName;
	private final boolean weekly;

	UsageType(String displayName, boolean weekly) {
		this.displayName = displayName;
		this.weekly = weekly;
	}

	/** True when the counter restarts every week; false for a lifetime allowance. Only weekly rows use {@code window_start}. */
	public boolean weekly() {
		return weekly;
	}

	public String displayName() {
		return displayName;
	}
}
