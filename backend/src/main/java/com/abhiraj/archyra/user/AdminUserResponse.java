package com.abhiraj.archyra.user;

import java.time.Instant;
import java.util.List;

/**
 * An account as an administrator sees it. Deliberately has no password hash, token or any secret, and shows the
 * plan that counts now (an expired PREMIUM reads as FREE) with the stored expiry beside it.
 */
public record AdminUserResponse(String id, String name, String email, Role role, Plan plan, Instant planExpiresAt,
		boolean emailVerified, boolean enabled, boolean locked, Instant createdAt, Instant lastLoginAt,
		List<Provider> linkedProviders) {
}
