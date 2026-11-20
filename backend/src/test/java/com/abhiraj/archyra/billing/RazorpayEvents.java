package com.abhiraj.archyra.billing;

import java.time.Instant;

/**
 * Webhook bodies in Razorpay's format (the {@code event} envelope, {@code payload.<entity>.entity}, amounts in paise, epoch
 * seconds), trimmed to what a real delivery always carries. Payments carry the customer's email and contact like real ones,
 * so the tests can prove neither is logged.
 */
final class RazorpayEvents {

	static final String CUSTOMER_EMAIL = "customer-in-payload@example.com";
	static final String CUSTOMER_CONTACT = "+919999999999";

	private RazorpayEvents() {
	}

	static String paymentEntity(String paymentId, String orderId, long amount, String currency, String status) {
		return """
				{"id":"%s","entity":"payment","amount":%d,"currency":"%s","status":"%s","order_id":%s,"invoice_id":null,
				 "method":"upi","captured":%s,"email":"%s","contact":"%s","notes":{},"error_reason":null,"created_at":1763600000}"""
				.formatted(paymentId, amount, currency, status, orderId == null ? "null" : "\"" + orderId + "\"",
						"captured".equals(status), CUSTOMER_EMAIL, CUSTOMER_CONTACT);
	}

	private static String envelope(String event, String contains, String payload) {
		return """
				{"entity":"event","account_id":"acc_FakeAccount01","event":"%s","contains":[%s],"payload":{%s},"created_at":1763600000}"""
				.formatted(event, contains, payload);
	}

	static String paymentCaptured(String paymentId, String orderId, long amount, String currency) {
		return envelope("payment.captured", "\"payment\"",
				"\"payment\":{\"entity\":" + paymentEntity(paymentId, orderId, amount, currency, "captured") + "}");
	}

	static String orderPaid(String paymentId, String orderId, long amount, String currency) {
		return envelope("order.paid", "\"payment\",\"order\"",
				"\"payment\":{\"entity\":" + paymentEntity(paymentId, orderId, amount, currency, "captured") + "},"
						+ "\"order\":{\"entity\":{\"id\":\"" + orderId + "\",\"entity\":\"order\",\"amount\":" + amount + ",\"amount_paid\":" + amount
						+ ",\"amount_due\":0,\"currency\":\"" + currency + "\",\"status\":\"paid\"}}");
	}

	static String paymentFailed(String paymentId, String orderId, long amount) {
		return envelope("payment.failed", "\"payment\"",
				"\"payment\":{\"entity\":" + paymentEntity(paymentId, orderId, amount, "INR", "failed") + "}");
	}

	static String subscriptionEntity(String subscriptionId, String status, Instant currentEnd, int paidCount) {
		return """
				{"id":"%s","entity":"subscription","plan_id":"%s","status":"%s","current_start":1763600000,"current_end":%s,
				 "paid_count":%d,"total_count":120,"customer_id":"cust_Fake0001","notes":{}}"""
				.formatted(subscriptionId, FakeRazorpay.PLAN_MONTHLY, status, currentEnd == null ? "null" : String.valueOf(currentEnd.getEpochSecond()), paidCount);
	}

	static String subscriptionEvent(String event, String subscriptionId, String status, Instant currentEnd) {
		return envelope(event, "\"subscription\"",
				"\"subscription\":{\"entity\":" + subscriptionEntity(subscriptionId, status, currentEnd, 1) + "}");
	}

	static String subscriptionCharged(String subscriptionId, String paymentId, long amount, Instant currentEnd) {
		return envelope("subscription.charged", "\"subscription\",\"payment\"",
				"\"subscription\":{\"entity\":" + subscriptionEntity(subscriptionId, "active", currentEnd, 1) + "},"
						+ "\"payment\":{\"entity\":" + paymentEntity(paymentId, "order_Inv" + FakeRazorpay.random(), amount, "INR", "captured") + "}");
	}

	static String refundProcessed(String paymentId, long amount) {
		return envelope("refund.processed", "\"refund\",\"payment\"",
				"\"refund\":{\"entity\":{\"id\":\"rfnd_" + FakeRazorpay.random() + "\",\"entity\":\"refund\",\"amount\":" + amount
						+ ",\"currency\":\"INR\",\"payment_id\":\"" + paymentId + "\",\"status\":\"processed\"}},"
						+ "\"payment\":{\"entity\":" + paymentEntity(paymentId, null, amount, "INR", "refunded") + "}");
	}

	static String eventId() {
		return "evt_" + FakeRazorpay.random();
	}
}
