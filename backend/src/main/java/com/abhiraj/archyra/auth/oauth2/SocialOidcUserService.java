package com.abhiraj.archyra.auth.oauth2;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.auth.oauth2.SocialAccountResolver.ProviderIdentity;
import com.abhiraj.archyra.user.Provider;

import lombok.extern.slf4j.Slf4j;

/**
 * Google sign-in (OpenID Connect). Spring has already validated the ID token; this reads {@code sub}, {@code email},
 * {@code email_verified} and {@code name} from it and resolves the Archyra account.
 */
@Slf4j
@Service
class SocialOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

	private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
	private final SocialAccountResolver resolver;

	@Autowired // the other constructor lets tests substitute the delegate
	SocialOidcUserService(SocialAccountResolver resolver) {
		this(new OidcUserService(), resolver);
	}

	SocialOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, SocialAccountResolver resolver) {
		this.delegate = delegate;
		this.resolver = resolver;
	}

	@Override
	public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
		OidcUser oidcUser = delegate.loadUser(request);
		try {
			Provider provider = Provider.fromRegistrationId(request.getClientRegistration().getRegistrationId());
			ProviderIdentity identity = new ProviderIdentity(provider, oidcUser.getSubject(), oidcUser.getEmail(),
					Boolean.TRUE.equals(oidcUser.getEmailVerified()), oidcUser.getFullName());
			var resolution = resolver.resolve(identity);
			return new SocialOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), oidcUser.getIdToken(),
					oidcUser.getUserInfo(), resolution.user().getId(), provider);
		}
		catch (SocialLoginException ex) {
			log.info("Social login refused: {}", ex.getMessage());
			throw new OAuth2AuthenticationException(new OAuth2Error(ex.code()));
		}
		catch (IllegalArgumentException | DataAccessException ex) { // unknown provider id, or a race on the unique email
			log.warn("Social login failed ({})", ex.getClass().getSimpleName());
			throw new OAuth2AuthenticationException(new OAuth2Error(SocialLoginErrors.FAILED));
		}
	}
}
