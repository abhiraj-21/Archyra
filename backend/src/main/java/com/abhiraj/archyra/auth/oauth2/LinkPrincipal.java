package com.abhiraj.archyra.auth.oauth2;

import com.abhiraj.archyra.user.IdentityLinkService.ProviderIdentityDetails;

/**
 * What a provider handshake in link mode hands to the success handler: only who the provider says the person is. It
 * names no Archyra account (that comes from the handshake session) and is deliberately not a {@link SocialPrincipal},
 * so it can never be mistaken for a login.
 */
interface LinkPrincipal {

	ProviderIdentityDetails identity();
}
