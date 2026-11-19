package com.abhiraj.archyra.common;

import org.springframework.http.HttpStatus;

/**
 * Deleting an account with the wrong method: a password account must send its password, an account without a password
 * must use the emailed confirmation link ({@code 400 DELETE_METHOD_MISMATCH}). The message never echoes input.
 */
public class AccountDeletionRuleException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	private AccountDeletionRuleException(String code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	/** The account has a password, but the emailed-link flow was used. */
	public static AccountDeletionRuleException useThePassword() {
		return new AccountDeletionRuleException("DELETE_METHOD_MISMATCH", HttpStatus.BAD_REQUEST,
				"This account has a password. Confirm the deletion with your password.");
	}

	/** The account has no password, but the password flow was used. */
	public static AccountDeletionRuleException useTheEmailedLink() {
		return new AccountDeletionRuleException("DELETE_METHOD_MISMATCH", HttpStatus.BAD_REQUEST,
				"This account has no password. Ask for a confirmation link by email instead.");
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}
}
