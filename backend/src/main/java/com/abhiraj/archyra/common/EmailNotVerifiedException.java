package com.abhiraj.archyra.common;

/** The password was right but the email has not been verified yet. */
public class EmailNotVerifiedException extends RuntimeException {

	public EmailNotVerifiedException() {
		super("Verify your email address before signing in");
	}
}
