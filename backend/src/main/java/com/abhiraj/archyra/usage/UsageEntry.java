package com.abhiraj.archyra.usage;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One line of {@link UsageResponse}. {@code limit} and {@code remaining} are null (not -1, not omitted) when the
 * plan is unlimited. {@code resetsAt} is the moment a weekly type starts again (next Monday 00:00 in the reset zone); a
 * lifetime type has none and the field is left out of the JSON. A PREMIUM user still gets it, but nothing is blocked.
 */
public record UsageEntry(UsageType usageType, int used, Integer limit, Integer remaining, boolean limitReached,
		@JsonInclude(JsonInclude.Include.NON_NULL) Instant resetsAt) {

	/** A FREE user: {@code remaining = max(limit - used, 0)}, and a user over the limit (a former premium user) is reached. */
	static UsageEntry limited(UsageType type, int used, int limit, Instant resetsAt) {
		return new UsageEntry(type, used, limit, Math.max(limit - used, 0), used >= limit, resetsAt);
	}

	/** A PREMIUM user: usage is still counted, but never capped. */
	static UsageEntry unlimited(UsageType type, int used, Instant resetsAt) {
		return new UsageEntry(type, used, null, null, false, resetsAt);
	}
}
