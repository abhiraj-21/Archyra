package com.abhiraj.archyra.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.ai.llm.LogCapture;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;

/** The public webhook route: no token, no CSRF, signature first, bounded body, nothing logged that should not be. */
class BillingWebhookIntegrationTest extends BillingIntegrationSupport {

	private MvcResult raw(String body, String signature) throws Exception {
		var request = post("/api/v1/billing/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Razorpay-Event-Id", RazorpayEvents.eventId());
		if (signature != null) {
			request.header("X-Razorpay-Signature", signature);
		}
		return mockMvc.perform(request).andReturn();
	}

	@Test
	void aSignedDeliveryNeedsNoTokenAndNoCsrfToken() throws Exception {
		User user = newUser();
		String orderId = createOrder(user, "MONTHLY");
		String body = RazorpayEvents.paymentCaptured(FakeRazorpay.paymentId(), orderId, 70000, "INR");

		// no Authorization header, no X-XSRF-TOKEN, no cookie
		MvcResult result = raw(body, FakeRazorpay.webhookSignature(body));

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.PREMIUM);
	}

	@Test
	void aBadMissingOrTamperedSignatureIsABare400WithNoSideEffects() throws Exception {
		User user = newUser();
		String orderId = createOrder(user, "MONTHLY");
		String body = RazorpayEvents.paymentCaptured(FakeRazorpay.paymentId(), orderId, 70000, "INR");
		String tampered = body.replace("70000", "70001");
		int eventsBefore = jdbc.queryForObject("SELECT count(*) FROM webhook_events", Integer.class);

		for (MvcResult result : new MvcResult[] { raw(body, null), raw(body, "0".repeat(64)), raw(body, "not-hex"),
				raw(tampered, FakeRazorpay.webhookSignature(body)), raw(body, PaymentSignatures.sign(FakeRazorpay.KEY_SECRET, body.getBytes())) }) {
			assertThat(result.getResponse().getStatus()).isEqualTo(400);
			assertThat(result.getResponse().getContentAsString()).isEmpty();
		}
		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE);
		assertThat(paymentRows(user, "CREATED")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events", Integer.class)).isEqualTo(eventsBefore);
	}

	@Test
	void anOversizeBodyIsRefusedBeforeTheSignatureIsChecked() throws Exception {
		String padding = "x".repeat(BillingWebhookController.MAX_BODY_BYTES);
		String body = "{\"event\":\"payment.captured\",\"padding\":\"" + padding + "\"}";

		assertThat(raw(body, FakeRazorpay.webhookSignature(body)).getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void anUnknownEventIsAcknowledgedAndIgnored() throws Exception {
		String body = "{\"entity\":\"event\",\"event\":\"invoice.expired\",\"payload\":{}}";

		assertThat(webhook(RazorpayEvents.eventId(), body).getResponse().getStatus()).isEqualTo(200);
		String notJson = "this is not json";
		assertThat(raw(notJson, FakeRazorpay.webhookSignature(notJson)).getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void aPaymentForAnUnknownOrderIsAcknowledgedAndChangesNothing() throws Exception {
		assertThat(webhook(RazorpayEvents.eventId(), RazorpayEvents.paymentCaptured(FakeRazorpay.paymentId(), "order_Unknown0001", 70000, "INR"))
				.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void aRefundIsRecordedAndDoesNotShortenPremium() throws Exception {
		User user = newUser();
		String paymentId = pay(user, "MONTHLY");
		Instant end = expiry(user);

		assertThat(webhook(RazorpayEvents.eventId(), RazorpayEvents.refundProcessed(paymentId, 70000)).getResponse().getStatus()).isEqualTo(200);

		assertThat(paymentRows(user, "REFUNDED")).isEqualTo(1);
		assertThat(expiry(user)).isEqualTo(end);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.PREMIUM);
	}

	@Test
	void aPaymentForADeletedAccountIsRecordedAndIgnored() throws Exception {
		User user = newUser();
		String orderId = createOrder(user, "MONTHLY");
		jdbc.update("DELETE FROM user_usage WHERE user_id = ?", user.getId());
		jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
		String paymentId = FakeRazorpay.paymentId();

		try (LogCapture logs = new LogCapture()) {
			assertThat(webhook(RazorpayEvents.eventId(), RazorpayEvents.paymentCaptured(paymentId, orderId, 70000, "INR")).getResponse().getStatus())
					.isEqualTo(200);
			assertThat(logs.texts()).anyMatch(line -> line.contains(paymentId) && line.contains("no longer exists"));
		}
		assertThat(paymentRows(user, "PAID")).isEqualTo(1);
	}

	@Test
	void noLogLineCarriesAKeyASignatureAPayloadOrCustomerDetails() throws Exception {
		User user = newUser();
		String body;
		String signature;
		try (LogCapture logs = new LogCapture()) {
			String orderId = createOrder(user, "MONTHLY");
			String paymentId = FakeRazorpay.paymentId();
			String wrong = FakeRazorpay.orderSignature(orderId, "pay_Wrong");
			postAs(user, "/api/v1/billing/orders/verify", "{\"razorpayOrderId\":\"" + orderId + "\",\"razorpayPaymentId\":\"" + paymentId
					+ "\",\"razorpaySignature\":\"" + wrong + "\"}");
			body = RazorpayEvents.paymentCaptured(paymentId, orderId, 70000, "INR");
			signature = FakeRazorpay.webhookSignature(body);
			raw(body, "f".repeat(64)); // refused
			raw(body, signature); // accepted
			String mismatch = RazorpayEvents.orderPaid(FakeRazorpay.paymentId(), createOrder(user, "YEARLY"), 1, "USD");
			webhook(RazorpayEvents.eventId(), mismatch);
			RAZORPAY.failEverything(500);
			postAs(user, "/api/v1/billing/orders", "{\"period\":\"MONTHLY\"}");

			// our own loggers at every level, everything else at INFO and above (production's level: framework DEBUG output such as
			// Hibernate's entity dump at a flush is not something the application writes)
			String all = String.join("\n", logs.events().stream()
					.filter(event -> event.getLoggerName().startsWith("com.abhiraj") || event.getLoggerName().equals("security.audit")
							|| event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO))
					.map(event -> event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
							: " " + ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())))
					.toList());
			assertThat(all).doesNotContain(FakeRazorpay.KEY_SECRET, FakeRazorpay.WEBHOOK_SECRET, wrong, signature, "f".repeat(64),
					RazorpayEvents.CUSTOMER_EMAIL, RazorpayEvents.CUSTOMER_CONTACT, user.getEmail(), "\"payload\"", "secret-detail-from-razorpay",
					FakeRazorpay.basicAuth().substring(6));
		}
	}
}
