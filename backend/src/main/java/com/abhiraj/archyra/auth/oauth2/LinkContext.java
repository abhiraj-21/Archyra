package com.abhiraj.archyra.auth.oauth2;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.abhiraj.archyra.user.Provider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * "This OAuth handshake is a link, not a login": the user, provider and deadline an accepted link intent copied into
 * the handshake session. It is the only thing that switches the callback into link mode, so it lives only as long as
 * that session, is cleared by every plain login request, and is destroyed with the session in every outcome.
 */
final class LinkContext {

	/** Where the SPA is sent after a link attempt (appended to the frontend base URL; never taken from a request). */
	static final String SETTINGS_PATH = "/settings";

	private static final String USER = LinkContext.class.getName() + ".USER";
	private static final String PROVIDER = LinkContext.class.getName() + ".PROVIDER";
	private static final String EXPIRES_AT = LinkContext.class.getName() + ".EXPIRES_AT";

	record Context(UUID userId, Provider provider, Instant expiresAt) {
	}

	private LinkContext() {
	}

	static void store(HttpSession session, Context context) {
		session.setAttribute(USER, context.userId().toString());
		session.setAttribute(PROVIDER, context.provider().name());
		session.setAttribute(EXPIRES_AT, context.expiresAt().toEpochMilli());
	}

	static void clear(HttpSession session) {
		if (session != null) {
			session.removeAttribute(USER);
			session.removeAttribute(PROVIDER);
			session.removeAttribute(EXPIRES_AT);
		}
	}

	static Optional<Context> read(HttpSession session) {
		if (session == null) {
			return Optional.empty();
		}
		try {
			Object user = session.getAttribute(USER);
			Object provider = session.getAttribute(PROVIDER);
			Object expires = session.getAttribute(EXPIRES_AT);
			if (user == null || provider == null || !(expires instanceof Long millis)) {
				return Optional.empty();
			}
			return Optional.of(new Context(UUID.fromString((String) user), Provider.valueOf((String) provider),
					Instant.ofEpochMilli(millis)));
		}
		catch (IllegalStateException | IllegalArgumentException | ClassCastException ex) { // invalidated, or tampered
			return Optional.empty();
		}
	}

	/**
	 * The link context of the request being handled on this thread, if any. The user-info services run inside the
	 * security filter chain and are given only the provider's response, so this is how they learn that the
	 * handshake is a link and must not resolve or create an account.
	 */
	static Optional<Context> current() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			HttpServletRequest request = attributes.getRequest();
			return read(request.getSession(false));
		}
		return Optional.empty();
	}
}
