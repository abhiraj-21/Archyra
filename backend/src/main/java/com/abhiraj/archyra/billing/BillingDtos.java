package com.abhiraj.archyra.billing;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The request and response bodies of {@code /api/v1/billing}. Times are ISO-8601 instants. */
final class BillingDtos {

	private BillingDtos() {
	}

	/** {@code POST /orders} and {@code POST /subscriptions}: the period is a string so that anything else is {@code INVALID_PERIOD}. */
	record PeriodRequest(String period) {
	}

	record CreateOrderResponse(String orderId, long amount, String currency, String keyId, String paymentId) {
	}

	record VerifyOrderRequest(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
	}

	record CreateSubscriptionResponse(String subscriptionId, String keyId, String period, long amount, String currency) {
	}

	record VerifySubscriptionRequest(String razorpaySubscriptionId, String razorpayPaymentId, String razorpaySignature) {
	}

	/**
	 * {@code GET /plans}: everything the payment screen shows, so no number is hard-coded in the browser. {@code keyId} is
	 * null while billing is off.
	 */
	record PlansResponse(boolean enabled, String keyId, String currency, List<Price> prices, Limits free, Limits premium,
			int aiQuestionsPerMinute, int keepRepositories, int graceDays, int reminderDays) {
	}

	/** @param autoRenewAvailable whether a Razorpay Plan is configured for this period */
	record Price(String period, long amountPaise, String display, boolean autoRenewAvailable) {
	}

	/** A null limit is unlimited. */
	record Limits(Integer repositoryAnalyses, Integer aiQuestionsPerWeek) {
	}

	/**
	 * {@code GET /status}.
	 *
	 * @param state NONE (never had Premium), ACTIVE, EXPIRING (ends within the reminder days and will not renew), GRACE (ended,
	 *              repositories not deleted yet) or LAPSED (had Premium, it ended and the grace period is over)
	 * @param grace only for EXPIRING and GRACE: when the repositories beyond the kept ones are deleted, and how many that is
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	record StatusResponse(String plan, String effectivePlan, Instant planExpiresAt, String state, AutoRenew autoRenew, Grace grace) {
	}

	/** @param active the subscription is live at Razorpay (it can still charge or is already cancelled at the cycle end) */
	record AutoRenew(boolean active, String period, boolean cancelAtCycleEnd, Instant nextChargeAt) {
	}

	record Grace(Instant endsAt, int repositoriesAtRisk) {
	}

	record RetentionResponse(int keep, List<RetentionItem> kept, List<RetentionItem> atRisk) {
	}

	record RetentionItem(String analysisId, String repositoryUrl, Instant lastActivityAt, String status) {
	}

	record PaymentItem(String id, String mode, String period, long amountPaise, String currency, String status, Instant createdAt,
			Instant paidAt, Instant extendedTo) {
	}

	record PaymentPage(List<PaymentItem> items, int page, int size, long totalElements, int totalPages) {
	}
}
