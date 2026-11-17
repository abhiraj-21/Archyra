package com.abhiraj.archyra.common;

/** Wrong email or password, or a locked account. Deliberately says nothing about which. */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Invalid email or password");
	}
}
