package com.abhiraj.archyra.auth.oauth2;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * The OAuth2 handshake is the only place Archyra uses an HTTP session, and only to remember the authorization
 * request (state, nonce) between the redirect to the provider and the callback. Once the handshake ends, in
 * success or failure, the session is destroyed and its cookie deleted.
 */
final class HandshakeSession {

	private static final String SESSION_COOKIE = "JSESSIONID";

	private HandshakeSession() {
	}

	static void discard(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		response.addHeader(HttpHeaders.SET_COOKIE,
				ResponseCookie.from(SESSION_COOKIE, "").path("/").maxAge(0).httpOnly(true).build().toString());
	}
}
