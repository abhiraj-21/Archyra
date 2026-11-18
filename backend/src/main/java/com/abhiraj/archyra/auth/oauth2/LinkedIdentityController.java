package com.abhiraj.archyra.auth.oauth2;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.common.LinkedIdentityRuleException;
import com.abhiraj.archyra.common.RateLimitedException;
import com.abhiraj.archyra.common.UserNotFoundException;
import com.abhiraj.archyra.security.CurrentUser;
import com.abhiraj.archyra.security.RateLimiter;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.user.IdentityLinkService;
import com.abhiraj.archyra.user.Provider;

import lombok.RequiredArgsConstructor;

/**
 * Sign-in methods of the signed-in user (Settings): list, start a link, unlink. Starting a link only issues a
 * short-lived intent; the link itself is completed by the OAuth2 handshake chain, see {@link LinkAuthorizationFilter}
 * and {@link OAuth2LinkHandler}. None of this touches the refresh cookie or any refresh-token family.
 */
@RestController
@RequestMapping("/api/v1/users/me/linked-identities")
@RequiredArgsConstructor
public class LinkedIdentityController {

	public record IdentityItem(String provider, String email, String login, Instant linkedAt) {
	}

	public record LinkedIdentitiesResponse(boolean hasPassword, List<IdentityItem> identities) {
	}

	public record LinkIntentResponse(String authorizationUrl) {
	}

	private final IdentityLinkService links;
	private final LinkIntentService intents;
	private final LinkBindingCookie bindingCookie;
	private final RateLimiter rateLimiter;
	private final SecurityAuditLogger audit;

	@GetMapping
	public LinkedIdentitiesResponse list(Authentication authentication) {
		var overview = links.overview(caller(authentication));
		return new LinkedIdentitiesResponse(overview.hasPassword(), overview.identities().stream()
				.map(view -> new IdentityItem(name(view.provider()), view.email(), view.login(), view.linkedAt())).toList());
	}

	@PostMapping("/{provider}/link-intent")
	public ResponseEntity<LinkIntentResponse> startLink(Authentication authentication, @PathVariable String provider) {
		Provider target = parse(provider);
		UUID userId = caller(authentication);
		var decision = rateLimiter.tryConsume(RateLimiter.Rule.LINK_INTENT, userId.toString());
		if (!decision.allowed()) {
			throw new RateLimitedException(decision.retryAfterSeconds());
		}
		links.requireEnabledAccount(userId);
		if (links.isLinked(userId, target)) {
			throw LinkedIdentityRuleException.providerAlreadyLinked();
		}
		LinkIntentService.Issued issued = intents.create(userId, target);
		audit.event("LINK_INTENT_CREATED_" + target, userId, null);
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, bindingCookie.set(issued.bindingSecret(), issued.ttl()))
				.body(new LinkIntentResponse("/oauth2/authorization/" + name(target) + "?link_intent=" + issued.token()));
	}

	@DeleteMapping("/{provider}")
	public ResponseEntity<Void> unlink(Authentication authentication, @PathVariable String provider) {
		links.unlink(caller(authentication), parse(provider));
		return ResponseEntity.noContent().build();
	}

	private static UUID caller(Authentication authentication) {
		return CurrentUser.id(authentication).orElseThrow(UserNotFoundException::new);
	}

	/** Only the two registration ids; anything else (including a different case) is a 400 that does not echo the input. */
	private static Provider parse(String registrationId) {
		for (Provider provider : Provider.values()) {
			if (name(provider).equals(registrationId)) {
				return provider;
			}
		}
		throw new InvalidRequestException("Unknown provider. Allowed values: google, github");
	}

	private static String name(Provider provider) {
		return provider.name().toLowerCase(Locale.ROOT);
	}
}
