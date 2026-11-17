package com.abhiraj.archyra.usage;

import java.util.List;

import com.abhiraj.archyra.user.Plan;

/**
 * Response body of {@code GET /api/v1/usage}: the plan that counts right now and one entry for every
 * {@link UsageType}.
 */
public record UsageResponse(Plan plan, List<UsageEntry> usage) {
}
