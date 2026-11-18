package com.abhiraj.archyra.auth.oauth2;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.Provider;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Runs before Spring's authorization-redirect filter on {@code GET /oauth2/authorization/{provider}} and decides
 * whether this handshake is a login or a link.
 *
 * <ul>
 * <li><b>No {@code link_intent}:</b> an ordinary login. Any link context left in the session is removed first, so a
 * later plain login can never be treated as a link.</li>
 * <li><b>With {@code link_intent}:</b> it must be a valid, unexpired, unused intent for this provider <i>and</i> the
 * binding cookie of the browser that asked for it must match. Only then is the intent consumed and its
 * {@code {userId, provider, expiresAt}} copied into a fresh handshake session. Otherwise the browser is sent to
 * {@code /settings?link_error=link_expired} and nothing is linked. Either way the binding cookie is deleted.</li>
 * </ul>
 *
 * Not a Spring bean on purpose (like {@code RateLimitFilter}): it is created inside the OAuth2 chain, so it is not
 * also registered as a servlet filter.
 */
class LinkAuthorizationFilter extends OncePerRequestFilter {

	static final String LINK_INTENT_PARAMETER = "link_intent";
	private static final String PREFIX = LinkBindingCookie.PATH + "/";
	private static final int MAX_TOKEN_LENGTH = 200;

	private final LinkIntentService intents;
	private final LinkBindingCookie bindingCookie;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;

	LinkAuthorizationFilter(LinkIntentService intents, LinkBindingCookie bindingCookie, SecurityProperties properties,
			SecurityAuditLogger audit) {
		this.intents = intents;
		this.bindingCookie = bindingCookie;
		this.properties = properties;
		this.audit = audit;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"GET".equals(request.getMethod()) || !request.getRequestURI().startsWith(PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String intent = request.getParameter(LINK_INTENT_PARAMETER);
		HttpSession existing = request.getSession(false);
		if (intent == null) {
			LinkContext.clear(existing); // a plain login: forget any stale link context
			chain.doFilter(request, response);
			return;
		}

		// A link handshake always starts from a fresh session, never one that was already in use.
		if (existing != null) {
			existing.invalidate();
		}
		response.addHeader(HttpHeaders.SET_COOKIE, bindingCookie.clear()); // the cookie is single use too

		Optional<LinkIntentService.Validated> validated = validate(request, intent);
		if (validated.isEmpty()) {
			audit.warn("OAUTH_LINK_INTENT_REJECTED", null, null);
			response.sendRedirect(OAuth2LoginFailureHandler.frontendUrl(properties, LinkContext.SETTINGS_PATH)
					+ "?link_error=" + SocialLoginErrors.LINK_EXPIRED);
			return;
		}
		LinkIntentService.Validated accepted = validated.get();
		LinkContext.store(request.getSession(true), new LinkContext.Context(accepted.userId(), accepted.provider(), accepted.expiresAt()));
		audit.event("OAUTH_LINK_STARTED_" + accepted.provider(), accepted.userId(), null);
		chain.doFilter(request, response);
	}

	private Optional<LinkIntentService.Validated> validate(HttpServletRequest request, String intent) {
		if (intent.isBlank() || intent.length() > MAX_TOKEN_LENGTH) {
			return Optional.empty();
		}
		Provider provider;
		try {
			provider = Provider.fromRegistrationId(request.getRequestURI().substring(PREFIX.length()));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		return intents.consume(intent, bindingCookie.read(request), provider);
	}
}
