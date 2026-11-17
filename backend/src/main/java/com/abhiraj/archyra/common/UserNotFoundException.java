package com.abhiraj.archyra.common;

/** No such user. Mapped to {@code 404 USER_NOT_FOUND}. */
public class UserNotFoundException extends RuntimeException {

	public UserNotFoundException() {
		super("User not found");
	}
}
