package com.abhiraj.archyra.auth.oauth2;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.auth.oauth2.SocialAccountResolver.ProviderIdentity;
import com.abhiraj.archyra.user.IdentityLinkService;
import com.abhiraj.archyra.user.Provider;

import lombok.extern.slf4j.Slf4j;

/**
 * GitHub sign-in (plain OAuth2). The profile gives the numeric id and name; the email comes from the emails API,
 * where only the primary verified address is accepted.
 */
@Slf4j
@Service
class SocialOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
	private final GitHubEmailClient emailClient;
	private final SocialAccountResolver resolver;

	@Autowired // the other constructor lets tests substitute the delegate
	SocialOAuth2UserService(GitHubEmailClient emailClient, SocialAccountResolver resolver) {
		this(new DefaultOAuth2UserService(), emailClient, resolver);
	}

	SocialOAuth2UserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate, GitHubEmailClient emailClient,
			SocialAccountResolver resolver) {
		this.delegate = delegate;
		this.emailClient = emailClient;
		this.resolver = resolver;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
		OAuth2User profile = delegate.loadUser(request);
		try {
			Provider provider = Provider.fromRegistrationId(request.getClientRegistration().getRegistrationId());
			if (provider != Provider.GITHUB) {
				throw new SocialLoginException(SocialLoginErrors.FAILED, "unsupported provider " + provider);
			}
			Object id = profile.getAttribute("id");
			if (id == null || String.valueOf(id).isBlank()) {
				throw new SocialLoginException(SocialLoginErrors.FAILED, "GitHub profile without an id");
			}
			if (LinkContext.current().isPresent()) {
				return linkPrincipal(request, profile, String.valueOf(id), provider);
			}
			String email = emailClient.primaryVerifiedEmail(request.getAccessToken().getTokenValue()).orElse(null);
			String name = profile.getAttribute("name") != null ? profile.getAttribute("name") : profile.getAttribute("login");
			var resolution = resolver.resolve(new ProviderIdentity(provider, String.valueOf(id), email, email != null, name));
			return new SocialOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), profile.getAttributes(), "id",
					resolution.user().getId(), provider);
		}
		catch (SocialLoginException ex) {
			log.info("Social login refused: {}", ex.getMessage());
			throw new OAuth2AuthenticationException(new OAuth2Error(ex.code()));
		}
		catch (IllegalArgumentException | DataAccessException ex) {
			log.warn("Social login failed ({})", ex.getClass().getSimpleName());
			throw new OAuth2AuthenticationException(new OAuth2Error(SocialLoginErrors.FAILED));
		}
	}

	/**
	 * Link mode: report who GitHub says the person is and nothing else; the account resolver is never called. The
	 * primary verified email is shown when GitHub gives one, but it is not required and its absence is not an error.
	 */
	private OAuth2User linkPrincipal(OAuth2UserRequest request, OAuth2User profile, String id, Provider provider) {
		String email = null;
		try {
			email = emailClient.primaryVerifiedEmail(request.getAccessToken().getTokenValue()).orElse(null);
		}
		catch (SocialLoginException ex) {
			log.info("No GitHub email for the link: {}", ex.getMessage());
		}
		Object login = profile.getAttribute("login");
		return new LinkOAuth2User(profile.getAttributes(),
				new IdentityLinkService.ProviderIdentityDetails(provider, id, email, login == null ? null : login.toString()));
	}
}
