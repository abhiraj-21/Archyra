package com.abhiraj.archyra.user;

/** Social login providers. The names match the Spring Security registration ids, upper-cased. */
public enum Provider {
	GOOGLE,
	GITHUB;

	/** @param registrationId e.g. {@code google}, as used in {@code /oauth2/authorization/{registrationId}} */
	public static Provider fromRegistrationId(String registrationId) {
		return valueOf(registrationId.toUpperCase(java.util.Locale.ROOT));
	}
}
