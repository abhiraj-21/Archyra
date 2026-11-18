package com.abhiraj.archyra.auth.oauth2;

import java.io.IOException;
import java.time.Clock;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.IdentityLinkService;
import com.abhiraj.archyra.user.IdentityLinkService.LinkResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ends a provider handshake that was started as a link. It never logs anyone in: no refresh token, no cookie, no
 * session principal. The account is the one bound into the handshake session when the link intent was accepted,
 * never one found by the provider's email, and the handshake session is destroyed in every outcome.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OAuth2LinkHandler {

	private final IdentityLinkService links;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;
	private final Clock clock;

	void complete(HttpServletRequest request, HttpServletResponse response, Authentication authentication,
			LinkContext.Context context) throws IOException {
		String error = linkOrExplain(authentication, context);
		HandshakeSession.discard(request, response); // clears the link context with the session, success or not
		if (error != null) {
			audit.eventWithCase("OAUTH_LINK_REFUSED", context.userId(), null, error);
			redirectError(response, properties, error);
			return;
		}
		response.sendRedirect(OAuth2LoginFailureHandler.frontendUrl(properties, LinkContext.SETTINGS_PATH)
				+ "?link=ok&provider=" + context.provider().name().toLowerCase(java.util.Locale.ROOT));
	}

	/** Returns null when the identity is now linked, otherwise the fixed {@code link_error} code. */
	private String linkOrExplain(Authentication authentication, LinkContext.Context context) {
		if (clock.instant().isAfter(context.expiresAt())) {
			return SocialLoginErrors.LINK_EXPIRED;
		}
		if (!(authentication.getPrincipal() instanceof LinkPrincipal principal)
				|| principal.identity().provider() != context.provider()) {
			return SocialLoginErrors.FAILED;
		}
		try {
			LinkResult result = links.link(context.userId(), principal.identity());
			return switch (result) {
				case LINKED, ALREADY_LINKED -> null; // linking the same identity again is a success
				case IDENTITY_OWNED_BY_ANOTHER_ACCOUNT -> SocialLoginErrors.IDENTITY_ALREADY_LINKED;
				case PROVIDER_ALREADY_LINKED -> SocialLoginErrors.PROVIDER_ALREADY_LINKED;
				case ACCOUNT_DISABLED -> SocialLoginErrors.ACCOUNT_DISABLED;
				case ACCOUNT_MISSING -> SocialLoginErrors.FAILED;
			};
		}
		catch (DataIntegrityViolationException ex) {
			// Another account claimed this provider identity between our check and the insert.
			return SocialLoginErrors.IDENTITY_ALREADY_LINKED;
		}
		catch (RuntimeException ex) {
			log.warn("Linking failed ({})", ex.getClass().getSimpleName());
			return SocialLoginErrors.FAILED;
		}
	}

	/** A failed link goes back to Settings with one code from the fixed allow-list. */
	static void redirectError(HttpServletResponse response, SecurityProperties properties, String code) throws IOException {
		String safe = SocialLoginErrors.LINK_ALL.contains(code) ? code : SocialLoginErrors.FAILED;
		response.sendRedirect(OAuth2LoginFailureHandler.frontendUrl(properties, LinkContext.SETTINGS_PATH) + "?link_error=" + safe);
	}
}
