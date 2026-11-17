package com.abhiraj.archyra.common;

/** An email verification or password reset token that is unknown, expired, already used or of the wrong kind. */
public class InvalidTokenException extends RuntimeException {

	public InvalidTokenException() {
		super("This link is invalid or has expired");
	}
}
