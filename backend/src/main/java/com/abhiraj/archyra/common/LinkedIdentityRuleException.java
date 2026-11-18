package com.abhiraj.archyra.common;

import org.springframework.http.HttpStatus;

/**
 * An account-linking request the rules refuse: a provider that is already linked, removing the last way to sign in, an
 * identity that is not linked. Carries its own error code and status ({@code 409 PROVIDER_ALREADY_LINKED},
 * {@code 409 LAST_LOGIN_METHOD}, {@code 404 IDENTITY_NOT_FOUND}). The message never names another account.
 */
public class LinkedIdentityRuleException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	private LinkedIdentityRuleException(String code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public static LinkedIdentityRuleException providerAlreadyLinked() {
		return new LinkedIdentityRuleException("PROVIDER_ALREADY_LINKED", HttpStatus.CONFLICT,
				"That sign-in provider is already linked to your account.");
	}

	public static LinkedIdentityRuleException lastLoginMethod() {
		return new LinkedIdentityRuleException("LAST_LOGIN_METHOD", HttpStatus.CONFLICT,
				"This is your only way to sign in. Set a password or link another provider first.");
	}

	public static LinkedIdentityRuleException identityNotFound() {
		return new LinkedIdentityRuleException("IDENTITY_NOT_FOUND", HttpStatus.NOT_FOUND,
				"That sign-in provider is not linked to your account.");
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}
}
