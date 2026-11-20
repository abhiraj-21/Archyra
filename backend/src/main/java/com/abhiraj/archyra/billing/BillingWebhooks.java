package com.abhiraj.archyra.billing;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * Processes one Razorpay webhook event whose signature was already checked ({@link BillingWebhookController}). The event id
 * is claimed in the same transaction as the work: a repeated delivery does nothing, and a delivery whose work failed rolls
 * the claim back, so Razorpay's retry runs it again. Unknown event types are acknowledged and ignored. Orders are matched
 * through our payment row, subscriptions through our subscription row, never through anything in the payload alone. Nothing
 * of the payload is logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class BillingWebhooks {

	enum Outcome {
		PROCESSED, DUPLICATE, IGNORED
	}

	private final WebhookEventRepository events;
	private final PaymentRepository payments;
	private final BillingService billing;
	private final SubscriptionService subscriptions;
	private final Clock clock;

	@Transactional
	Outcome handle(String eventId, String type, JsonNode event) {
		String kind = type == null ? "" : type;
		if (events.claim(eventId, kind.length() > 60 ? kind.substring(0, 60) : kind, clock.instant()) == 0) {
			return Outcome.DUPLICATE;
		}
		JsonNode payload = event.path("payload");
		JsonNode payment = payload.path("payment").path("entity");
		JsonNode subscription = payload.path("subscription").path("entity");
		switch (kind) {
			case "payment.captured" -> orderPaid(payment.path("order_id").asString(""), payment);
			case "order.paid" -> orderPaid(payload.path("order").path("entity").path("id").asString(""), payment);
			case "payment.failed" -> payments.findByRazorpayOrderId(payment.path("order_id").asString(""))
					.ifPresent(row -> payments.markFailed(row.getId()));
			case "subscription.authenticated" -> subscriptionStatus(subscription, SubscriptionStatus.AUTHENTICATED);
			case "subscription.activated" -> subscriptionStatus(subscription, SubscriptionStatus.ACTIVE);
			case "subscription.pending" -> subscriptionStatus(subscription, SubscriptionStatus.PENDING);
			case "subscription.halted" -> subscriptionStatus(subscription, SubscriptionStatus.HALTED);
			case "subscription.cancelled" -> subscriptionStatus(subscription, SubscriptionStatus.CANCELLED);
			case "subscription.completed" -> subscriptionStatus(subscription, SubscriptionStatus.COMPLETED);
			case "subscription.charged" -> subscriptions.onCharged(subscription.path("id").asString(""), epoch(subscription.path("current_end")),
					payment.path("id").asString(""), payment.path("amount").asLong(-1), payment.path("currency").asString(null));
			// recorded only: a refund never shortens Premium by itself (an administrator decides, PUT /admin/users/{id}/plan)
			case "refund.processed" -> payments.markRefunded(payload.path("refund").path("entity").path("payment_id").asString(""));
			default -> {
				return Outcome.IGNORED;
			}
		}
		return Outcome.PROCESSED;
	}

	/** {@code payment.captured} and {@code order.paid} of a one-time order. A subscription's invoice order is not ours: ignored. */
	private void orderPaid(String orderId, JsonNode payment) {
		if (!BillingService.validId(orderId)) {
			return;
		}
		payments.findByRazorpayOrderId(orderId).ifPresentOrElse(row -> {
			String paymentId = payment.path("id").asString("");
			if (!BillingService.validId(paymentId)) {
				log.warn("An order payment without a usable payment id was ignored");
				return;
			}
			billing.applyPayment(row.getId(), paymentId, payment.path("amount").asLong(-1), payment.path("currency").asString(null));
		}, () -> log.info("A payment for an order we do not hold was ignored"));
	}

	private void subscriptionStatus(JsonNode subscription, SubscriptionStatus status) {
		subscriptions.onStatus(subscription.path("id").asString(""), status, epoch(subscription.path("current_end")));
	}

	private static Instant epoch(JsonNode seconds) {
		long value = seconds.asLong(0);
		return value > 0 ? Instant.ofEpochSecond(value) : null;
	}
}
