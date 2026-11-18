package com.abhiraj.archyra.auth.oauth2;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import com.abhiraj.archyra.user.IdentityLinkService.ProviderIdentityDetails;

/** The principal of a Google handshake in link mode. */
class LinkOidcUser extends DefaultOidcUser implements LinkPrincipal {

	private final ProviderIdentityDetails identity;

	LinkOidcUser(OidcIdToken idToken, OidcUserInfo userInfo, ProviderIdentityDetails identity) {
		super(List.of(new SimpleGrantedAuthority("ROLE_LINK_ONLY")), idToken, userInfo, "sub");
		this.identity = identity;
	}

	@Override
	public ProviderIdentityDetails identity() {
		return identity;
	}
}
