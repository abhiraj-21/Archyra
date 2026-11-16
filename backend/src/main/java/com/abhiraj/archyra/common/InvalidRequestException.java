package com.abhiraj.archyra.common;

/** A request parameter is well formed but not acceptable; mapped to HTTP 400 {@code VALIDATION_ERROR}. */
public class InvalidRequestException extends RuntimeException {

	public InvalidRequestException(String message) {
		super(message);
	}
}
