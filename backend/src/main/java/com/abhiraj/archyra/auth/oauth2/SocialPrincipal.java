package com.abhiraj.archyra.auth.oauth2;

import java.util.UUID;

import com.abhiraj.archyra.user.Provider;

/** What a successful social login hands to the success handler: which Archyra account signed in, and through whom. */
interface SocialPrincipal {

	UUID archyraUserId();

	Provider provider();
}
