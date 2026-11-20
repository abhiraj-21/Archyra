package com.abhiraj.archyra.billing;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/**
 * {@code /api/v1/billing}: Premium through Razorpay. Every route needs a signed-in caller and acts on the caller only; the
 * webhook is the separate {@link BillingWebhookController}. Amounts and plan ids come from configuration, never from a body.
 */
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
class BillingController {

	private final BillingService billing;
	private final SubscriptionService subscriptions;

	@GetMapping("/plans")
	BillingDtos.PlansResponse plans() {
		return billing.plans();
	}

	@GetMapping("/status")
	BillingDtos.StatusResponse status() {
		return billing.status(caller());
	}

	@GetMapping("/retention")
	BillingDtos.RetentionResponse retention() {
		return billing.retention(caller());
	}

	@GetMapping("/payments")
	BillingDtos.PaymentPage payments(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return billing.payments(caller(), page, size);
	}

	@PostMapping("/orders")
	BillingDtos.CreateOrderResponse createOrder(@RequestBody(required = false) BillingDtos.PeriodRequest request) {
		return billing.createOrder(caller(), request == null ? null : request.period());
	}

	@PostMapping("/orders/verify")
	BillingDtos.StatusResponse verifyOrder(@RequestBody(required = false) BillingDtos.VerifyOrderRequest request) {
		return billing.verifyOrder(caller(), request);
	}

	@PostMapping("/subscriptions")
	BillingDtos.CreateSubscriptionResponse createSubscription(@RequestBody(required = false) BillingDtos.PeriodRequest request) {
		return subscriptions.create(caller(), request == null ? null : request.period());
	}

	@PostMapping("/subscriptions/verify")
	BillingDtos.StatusResponse verifySubscription(@RequestBody(required = false) BillingDtos.VerifySubscriptionRequest request) {
		return subscriptions.verify(caller(), request);
	}

	@PostMapping("/subscriptions/cancel")
	BillingDtos.StatusResponse cancelSubscription() {
		return subscriptions.cancel(caller());
	}

	private static UUID caller() {
		return CurrentUser.id().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No signed-in user"));
	}
}
