package com.abhiraj.archyra.billing;

/** How a payment was made: a one-time Razorpay Order, or one charge of an auto-renewing Razorpay Subscription. */
public enum PaymentMode {
	ORDER,
	SUBSCRIPTION
}
