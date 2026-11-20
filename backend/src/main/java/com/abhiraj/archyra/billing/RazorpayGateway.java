package com.abhiraj.archyra.billing;

import java.time.Instant;
import java.util.Map;

/**
 * The Razorpay API, as far as Archyra uses it. Everything else in {@code billing/} depends on this interface; the one
 * implementation is a plain {@code RestClient} ({@link RazorpayRestGateway}) and tests point it at a WireMock fake that
 * speaks the real wire format. Implementations never log keys, request or response bodies, or customer data, and throw
 * {@link RazorpayUnavailableException} (fixed text, no body) for anything that is not a success.
 */
interface RazorpayGateway {

	/** An order as Razorpay created it. */
	record Order(String id, long amount, String currency, String receipt, String status) {
	}

	/**
	 * A subscription as Razorpay reports it.
	 *
	 * @param status     Razorpay's lower-case status ({@code created}, {@code authenticated}, {@code active}, ...)
	 * @param currentEnd the end of the current cycle, or null before the first charge
	 * @param paidCount  how many cycles were charged
	 */
	record RemoteSubscription(String id, String planId, String status, Instant currentEnd, int paidCount) {
	}

	/** {@code POST /v1/orders}. {@code notes} must never hold an email or a name. */
	Order createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes);

	/** {@code POST /v1/subscriptions}, starting at once (the first cycle is charged when the customer authorises it). */
	RemoteSubscription createSubscription(String planId, int totalCount, Map<String, String> notes);

	/** {@code POST /v1/subscriptions/{id}/cancel}; at the end of the current cycle, or at once. */
	RemoteSubscription cancelSubscription(String subscriptionId, boolean atCycleEnd);

	/** {@code GET /v1/subscriptions/{id}}. */
	RemoteSubscription fetchSubscription(String subscriptionId);
}
