package com.abhiraj.archyra.billing;

/**
 * {@code CREATED} until the money arrives; {@code PAID} once Premium was extended for it (exactly once, by a guarded UPDATE);
 * {@code FAILED} after an attempt failed (Razorpay lets the customer try the same order again, so a later success still
 * moves it to {@code PAID}); {@code REFUNDED} is recorded from the webhook and never shortens Premium by itself.
 */
public enum PaymentStatus {
	CREATED,
	PAID,
	FAILED,
	REFUNDED
}
