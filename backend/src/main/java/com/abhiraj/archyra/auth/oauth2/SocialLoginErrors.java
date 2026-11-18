package com.abhiraj.archyra.auth.oauth2;

import java.util.Set;

/**
 * The only error codes that can appear in a failure redirect: {@link #ALL} for a login ({@code /login?error=<code>}),
 * {@link #LINK_ALL} for a link ({@code /settings?link_error=<code>}).
 */
final class SocialLoginErrors {

	static final String FAILED = "oauth_failed";
	static final String EMAIL_UNVERIFIED = "oauth_email_unverified";
	static final String ACCOUNT_DISABLED = "oauth_account_disabled";
	static final String CANCELLED = "oauth_cancelled";

	static final Set<String> ALL = Set.of(FAILED, EMAIL_UNVERIFIED, ACCOUNT_DISABLED, CANCELLED);

	// Link mode (Settings → Sign-in methods): the codes of {@code /settings?link_error=<code>}.
	static final String IDENTITY_ALREADY_LINKED = "identity_already_linked";
	static final String PROVIDER_ALREADY_LINKED = "provider_already_linked";
	static final String LINK_EXPIRED = "link_expired";

	static final Set<String> LINK_ALL = Set.of(CANCELLED, FAILED, ACCOUNT_DISABLED, IDENTITY_ALREADY_LINKED,
			PROVIDER_ALREADY_LINKED, LINK_EXPIRED);

	private SocialLoginErrors() {
	}
}
