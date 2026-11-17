package com.abhiraj.archyra.common;

/** An administrator action that the rules forbid (disabling yourself, disabling the last administrator). 409 with its own code. */
public class AdminRuleViolationException extends RuntimeException {

	private final String code;

	public AdminRuleViolationException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
