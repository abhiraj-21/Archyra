package com.abhiraj.archyra.user;

/**
 * Response body of {@code GET /api/v1/admin/stats}.
 *
 * @param totalUsers    every registered account, enabled or not
 * @param activeUsers   enabled accounts that signed in during the last 30 days
 * @param premiumUsers  accounts whose PREMIUM plan has not expired
 * @param totalAnalyses every analysis of every user, whatever its status
 */
public record AdminStatsResponse(long totalUsers, long activeUsers, long premiumUsers, long totalAnalyses) {
}
