package com.abhiraj.archyra.common;

/** The service cannot take more work right now; mapped to HTTP 503. */
public class ServiceBusyException extends RuntimeException {

	public ServiceBusyException(String message) {
		super(message);
	}
}
