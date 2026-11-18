package com.abhiraj.archyra.auth.oauth2;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.security.SecurityProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Sends the browser back to the SPA's login page with {@code ?error=<code>}, where the code is one of the fixed
 * values in {@link SocialLoginErrors}. No exception message, provider text or request value ever reaches the URL.
 */
@Component
@RequiredArgsConstructor
class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

	/** The provider's own code when the user declines the consent screen. */
	private static final String PROVIDER_ACCESS_DENIED = "access_denied";

	private final SecurityProperties properties;

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		if (LinkContext.read(request.getSession(false)).isPresent()) {
			// A link, not a login: back to Settings, and the link context goes with the session.
			HandshakeSession.discard(request, response);
			OAuth2LinkHandler.redirectError(response, properties, linkCodeFor(exception));
			return;
		}
		redirect(request, response, codeFor(exception));
	}

	/** The login code mapped onto the link allow-list; anything else (an unverified email does not apply) is a plain failure. */
	static String linkCodeFor(AuthenticationException exception) {
		String code = codeFor(exception);
		return SocialLoginErrors.LINK_ALL.contains(code) ? code : SocialLoginErrors.FAILED;
	}

	void redirect(HttpServletRequest request, HttpServletResponse response, String code) throws IOException {
		HandshakeSession.discard(request, response);
		String safeCode = SocialLoginErrors.ALL.contains(code) ? code : SocialLoginErrors.FAILED;
		response.sendRedirect(frontendUrl(properties, properties.oauth2().failureRedirectPath()) + "?error=" + safeCode);
	}

	static String codeFor(AuthenticationException exception) {
		if (exception instanceof OAuth2AuthenticationException oauth) {
			String code = oauth.getError().getErrorCode();
			if (SocialLoginErrors.ALL.contains(code)) {
				return code;
			}
			if (PROVIDER_ACCESS_DENIED.equals(code)) {
				return SocialLoginErrors.CANCELLED;
			}
		}
		return SocialLoginErrors.FAILED;
	}

	/** The SPA's base URL from configuration plus a configured path. */
	static String frontendUrl(SecurityProperties properties, String path) {
		String base = properties.frontendBaseUrl();
		return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
	}
}
