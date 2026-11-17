package com.abhiraj.archyra.auth.oauth2;

/**
 * A social login that must not go ahead. {@code code} is one of the fixed values of {@link SocialLoginErrors}; the
 * message is for the log only and never reaches the browser.
 */
class SocialLoginException extends RuntimeException {

	private final String code;

	SocialLoginException(String code, String logMessage) {
		super(logMessage);
		this.code = code;
	}

	String code() {
		return code;
	}
}
