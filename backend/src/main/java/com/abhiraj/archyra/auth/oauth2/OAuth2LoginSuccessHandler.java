package com.abhiraj.archyra.auth.oauth2;

import java.io.IOException;
import java.time.Clock;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.auth.ClientInfo;
import com.abhiraj.archyra.auth.RefreshCookies;
import com.abhiraj.archyra.auth.RefreshTokenService;
import com.abhiraj.archyra.security.ClientIpResolver;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Ends a successful social login. It starts a refresh-token family and sets the refresh cookie, destroys the
 * handshake session and redirects the browser to the SPA's callback page, which then calls {@code /auth/refresh}
 * to obtain the access token exactly as after a password login. Nothing secret is placed in the URL, and the URL
 * itself comes only from configuration.
 */
@Component
@RequiredArgsConstructor
class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

	private final RefreshTokenService refreshTokens;
	private final RefreshCookies cookies;
	private final UserService userService;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;
	private final OAuth2LoginFailureHandler failureHandler;
	private final ClientIpResolver clientIps;
	private final Clock clock;
	private final OAuth2LinkHandler linkHandler;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		// A handshake started from Settings is a link, not a login: no tokens, no cookies, handled on its own.
		var link = LinkContext.read(request.getSession(false));
		if (link.isPresent()) {
			linkHandler.complete(request, response, authentication, link.get());
			return;
		}
		if (!(authentication.getPrincipal() instanceof SocialPrincipal principal)) {
			failureHandler.redirect(request, response, SocialLoginErrors.FAILED);
			return;
		}
		ClientInfo client = new ClientInfo(clientIps.resolve(request), request.getHeader(HttpHeaders.USER_AGENT));
		var refreshToken = refreshTokens.startFamily(principal.archyraUserId(), client);
		userService.recordSuccessfulLogin(principal.archyraUserId(), clock.instant());
		audit.event("OAUTH_LOGIN_" + principal.provider(), principal.archyraUserId(), client.ip());

		HandshakeSession.discard(request, response);
		response.addHeader(HttpHeaders.SET_COOKIE, cookies.set(refreshToken));
		response.sendRedirect(OAuth2LoginFailureHandler.frontendUrl(properties, properties.oauth2().successRedirectPath()));
	}
}
