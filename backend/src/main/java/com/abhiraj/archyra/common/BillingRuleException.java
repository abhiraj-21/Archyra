package com.abhiraj.archyra.common;

import org.springframework.http.HttpStatus;

/**
 * A billing request the rules refuse. Carries its own code and status; the message is fixed text and never echoes a
 * request, a Razorpay response, a signature or a key.
 */
public class BillingRuleException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	private BillingRuleException(String code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	/** 503: Razorpay is not configured, or could not be reached. Nothing was charged. */
	public static BillingRuleException unavailable() {
		return new BillingRuleException("BILLING_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
				"Payments are not available right now. Nothing was charged; please try again later.");
	}

	/** 400: the checkout's signature did not match, or the order or subscription is not yours. */
	public static BillingRuleException verificationFailed() {
		return new BillingRuleException("PAYMENT_VERIFICATION_FAILED", HttpStatus.BAD_REQUEST,
				"The payment could not be verified. If money left your account, Premium starts as soon as Razorpay confirms it.");
	}

	/** 409: an auto-renewing subscription is active; cancel it before buying anything else. */
	public static BillingRuleException subscriptionActive() {
		return new BillingRuleException("SUBSCRIPTION_ACTIVE", HttpStatus.CONFLICT,
				"Your Premium renews automatically. Cancel auto-renew first if you want to pay another way.");
	}

	/** 409: there is no auto-renewing subscription to cancel. */
	public static BillingRuleException noActiveSubscription() {
		return new BillingRuleException("NO_ACTIVE_SUBSCRIPTION", HttpStatus.CONFLICT, "You have no auto-renewing subscription to cancel.");
	}

	/** 400: the period is not MONTHLY or YEARLY. */
	public static BillingRuleException invalidPeriod() {
		return new BillingRuleException("INVALID_PERIOD", HttpStatus.BAD_REQUEST, "period must be MONTHLY or YEARLY");
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}
}
