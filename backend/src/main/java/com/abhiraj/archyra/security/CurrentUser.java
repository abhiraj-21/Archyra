package com.abhiraj.archyra.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is calling: the user id carried as the access token's {@code sub}. */
public final class CurrentUser {

	private CurrentUser() {
	}

	/** The id of the authenticated caller, if there is one and the name is a user id. */
	public static Optional<UUID> id(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(authentication.getName()));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	public static Optional<UUID> id() {
		return id(SecurityContextHolder.getContext().getAuthentication());
	}
}
