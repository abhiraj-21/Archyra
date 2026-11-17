package com.abhiraj.archyra.auth.oauth2;

import java.util.Collection;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import com.abhiraj.archyra.user.Provider;

/** The signed-in principal of an OpenID Connect login (Google). */
class SocialOidcUser extends DefaultOidcUser implements SocialPrincipal {

	private final UUID archyraUserId;
	private final Provider provider;

	SocialOidcUser(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken, OidcUserInfo userInfo,
			UUID archyraUserId, Provider provider) {
		super(authorities, idToken, userInfo, "sub");
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
