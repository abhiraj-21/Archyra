package com.abhiraj.archyra.user;

import java.util.List;

/**
 * What a signed-in user may see about themselves ({@code GET /api/v1/auth/me}, {@code /api/v1/users/me}). The plan is
 * the effective one: an expired PREMIUM shows as FREE.
 */
public record UserProfile(String id, String name, String email, Role role, Plan plan, boolean emailVerified,
		List<Provider> linkedProviders) {
}
