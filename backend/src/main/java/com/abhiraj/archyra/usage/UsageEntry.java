package com.abhiraj.archyra.usage;

/**
 * One line of {@link UsageResponse}. {@code limit} and {@code remaining} are null (not -1, not omitted) when the
 * plan is unlimited.
 */
public record UsageEntry(UsageType usageType, int used, Integer limit, Integer remaining, boolean limitReached) {

	/** A FREE user: {@code remaining = max(limit - used, 0)}, and a user over the limit (a former premium user) is reached. */
	static UsageEntry limited(UsageType type, int used, int limit) {
		return new UsageEntry(type, used, limit, Math.max(limit - used, 0), used >= limit);
	}

	/** A PREMIUM user: usage is still counted, but never capped. */
	static UsageEntry unlimited(UsageType type, int used) {
		return new UsageEntry(type, used, null, null, false);
	}
}
