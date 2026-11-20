package com.abhiraj.archyra.billing;

/**
 * Razorpay could not be asked or did not say yes. The message is fixed: never a response body, a key or a customer detail.
 * {@code status} is the HTTP status Razorpay answered with (0 when it could not be reached), for the log only.
 */
class RazorpayUnavailableException extends RuntimeException {

	private final int status;

	RazorpayUnavailableException(String operation, int status) {
		super("Razorpay " + operation + " failed" + (status > 0 ? " with HTTP " + status : " (unreachable)"));
		this.status = status;
	}

	int status() {
		return status;
	}
}
