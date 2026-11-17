package com.abhiraj.archyra.auth.oauth2;

import java.util.Set;

/** The only error codes that can appear in the failure redirect ({@code /login?error=<code>}). */
final class SocialLoginErrors {

	static final String FAILED = "oauth_failed";
	static final String EMAIL_UNVERIFIED = "oauth_email_unverified";
	static final String ACCOUNT_DISABLED = "oauth_account_disabled";
	static final String CANCELLED = "oauth_cancelled";

	static final Set<String> ALL = Set.of(FAILED, EMAIL_UNVERIFIED, ACCOUNT_DISABLED, CANCELLED);

	private SocialLoginErrors() {
	}
}
