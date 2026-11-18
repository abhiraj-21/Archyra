package com.abhiraj.archyra.auth.oauth2;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.abhiraj.archyra.user.IdentityLinkService.ProviderIdentityDetails;

/** The principal of a GitHub handshake in link mode. */
class LinkOAuth2User extends DefaultOAuth2User implements LinkPrincipal {

	private final ProviderIdentityDetails identity;

	LinkOAuth2User(Map<String, Object> attributes, ProviderIdentityDetails identity) {
		super(List.of(new SimpleGrantedAuthority("ROLE_LINK_ONLY")), attributes, "id");
		this.identity = identity;
	}

	@Override
	public ProviderIdentityDetails identity() {
		return identity;
	}
}
