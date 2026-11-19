package com.abhiraj.archyra.common;

import org.springframework.http.HttpStatus;

/**
 * A password change the rules refuse for a reason that is about the account, not the new password's shape (that is an
 * {@link InvalidRequestException}): the current password is wrong ({@code 400 INCORRECT_PASSWORD}, which also covers a
 * locked account), or the account has no password to change ({@code 409 NO_PASSWORD_SET}). Messages never echo input.
 */
public class PasswordRuleException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	private PasswordRuleException(String code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public static PasswordRuleException incorrectPassword() {
		return new PasswordRuleException("INCORRECT_PASSWORD", HttpStatus.BAD_REQUEST, "Your current password is not correct.");
	}

	public static PasswordRuleException noPasswordSet() {
		return new PasswordRuleException("NO_PASSWORD_SET", HttpStatus.CONFLICT,
				"This account has no password yet. Use \"Forgot password\" with your account email to set one.");
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}
}
