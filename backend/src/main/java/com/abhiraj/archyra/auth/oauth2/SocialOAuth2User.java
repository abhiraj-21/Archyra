package com.abhiraj.archyra.auth.oauth2;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.abhiraj.archyra.user.Provider;

/** The signed-in principal of a plain OAuth2 login (GitHub). */
class SocialOAuth2User extends DefaultOAuth2User implements SocialPrincipal {

	private final UUID archyraUserId;
	private final Provider provider;

	SocialOAuth2User(Collection<? extends GrantedAuthority> authorities, Map<String, Object> attributes,
			String nameAttributeKey, UUID archyraUserId, Provider provider) {
		super(authorities, attributes, nameAttributeKey);
		this.archyraUserId = archyraUserId;
		this.provider = provider;
	}

	@Override
	public UUID archyraUserId() {
		return archyraUserId;
	}

	@Override
	public Provider provider() {
		return provider;
	}
}
