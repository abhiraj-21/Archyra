package com.abhiraj.archyra.security;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** Runs code as a signed-in user (as the access token filter would have set it up) from a test thread. */
public final class TestCaller {

	private TestCaller() {
	}

	public static <T> T runAs(UUID userId, Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId.toString(), "n/a",
				AuthorityUtils.createAuthorityList("ROLE_USER")));
		SecurityContextHolder.setContext(context);
		try {
			return action.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}
}
