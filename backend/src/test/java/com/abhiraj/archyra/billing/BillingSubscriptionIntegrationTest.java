package com.abhiraj.archyra.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;

/** Auto-renew: Razorpay Subscriptions, their checkout, their webhook events, cancelling, and deleting the account. */
class BillingSubscriptionIntegrationTest extends BillingIntegrationSupport {

	private static final Instant FIRST_CYCLE_END = Instant.parse("2026-12-20T10:00:00Z");

	private String subscribe(User user, String period) throws Exception {
		MvcResult created = postAs(user, "/api/v1/billing/subscriptions", "{\"period\":\"" + period + "\"}");
		assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(200);
		assertThat((String) json(created, "$.keyId")).isEqualTo(FakeRazorpay.KEY_ID);
		return json(created, "$.subscriptionId");
	}

	private MvcResult verify(User user, String subscriptionId, String paymentId) throws Exception {
		return postAs(user, "/api/v1/billing/subscriptions/verify", """
				{"razorpaySubscriptionId":"%s","razorpayPaymentId":"%s","razorpaySignature":"%s"}"""
				.formatted(subscriptionId, paymentId, FakeRazorpay.subscriptionSignature(paymentId, subscriptionId)));
	}

	/** Subscribes, authorises the first charge in the checkout and verifies it. Returns the subscription id. */
	private String activeSubscription(User user, String paymentId) throws Exception {
		String subscriptionId = subscribe(user, "MONTHLY");
		RAZORPAY.subscriptionReads(subscriptionId, "active", FIRST_CYCLE_END, 1);
		assertThat(verify(user, subscriptionId, paymentId).getResponse().getStatus()).isEqualTo(200);
		return subscriptionId;
	}

	private String subscriptionStatus(User user) {
		return jdbc.queryForObject("SELECT status FROM subscriptions WHERE user_id = ?", String.class, user.getId());
	}

	@Test
	void creatingASubscriptionUsesThePlanOfThePeriodForTenYears() throws Exception {
		User user = newUser();

		String subscriptionId = subscribe(user, "YEARLY");

		assertThat(subscriptionId).startsWith("sub_");
		RAZORPAY.server.verify(postRequestedFor(urlEqualTo("/v1/subscriptions")).withRequestBody(equalToJson(
				"{\"plan_id\":\"" + FakeRazorpay.PLAN_YEARLY + "\",\"total_count\":10,\"quantity\":1,\"customer_notify\":1,\"notes\":{\"product\":\"archyra-premium\",\"period\":\"YEARLY\"}}")));
		assertThat(subscriptionStatus(user)).isEqualTo("CREATED");
		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE); // nothing is granted before money arrives
	}

	@Test
	void theFirstChargeAppliedByVerifyAndBySubscriptionChargedExtendsOnceAndEachLaterCycleOnce() throws Exception {
		User user = newUser();
		String firstPayment = FakeRazorpay.paymentId();

		String subscriptionId = activeSubscription(user, firstPayment);

		assertThat(expiry(user)).isEqualTo(FIRST_CYCLE_END);
		assertThat(subscriptionStatus(user)).isEqualTo("ACTIVE");
		// the webhook of the same first charge, delivered twice
		String charged = RazorpayEvents.subscriptionCharged(subscriptionId, firstPayment, 70000, FIRST_CYCLE_END);
		String eventId = RazorpayEvents.eventId();
		webhook(eventId, charged);
		webhook(eventId, charged);
		webhook(RazorpayEvents.eventId(), charged); // and once more under another event id
		assertThat(expiry(user)).isEqualTo(FIRST_CYCLE_END);

		// the next cycle, a month later
		clock.setTo(FIRST_CYCLE_END.minus(Duration.ofMinutes(5)));
		String second = RazorpayEvents.subscriptionCharged(subscriptionId, FakeRazorpay.paymentId(), 70000, Instant.parse("2027-01-20T10:00:00Z"));
		assertThat(webhook(RazorpayEvents.eventId(), second).getResponse().getStatus()).isEqualTo(200);
		assertThat(webhook(RazorpayEvents.eventId(), second).getResponse().getStatus()).isEqualTo(200);
		assertThat(expiry(user)).isEqualTo(Instant.parse("2027-01-20T10:00:00Z"));
		assertThat(paymentRows(user, "PAID")).isEqualTo(2);
		assertThat(mail.billingTo(user.getEmail(), "PREMIUM_ACTIVATED")).hasSize(2)
				.allSatisfy(sent -> assertThat(((com.abhiraj.archyra.mail.BillingMails.PremiumActivated) sent.mail()).autoRenew()).isTrue());

		MvcResult status = getAs(user, "/api/v1/billing/status");
		assertThat((Boolean) json(status, "$.autoRenew.active")).isTrue();
		assertThat((String) json(status, "$.autoRenew.period")).isEqualTo("MONTHLY");
		assertThat((String) json(status, "$.autoRenew.nextChargeAt")).isEqualTo("2027-01-20T10:00:00Z");
	}

	@Test
	void theWebhookAloneActivatesAndChargesWhenTheCheckoutNeverCalledBack() throws Exception {
		User user = newUser();
		String subscriptionId = subscribe(user, "MONTHLY");

		webhook(RazorpayEvents.eventId(), RazorpayEvents.subscriptionEvent("subscription.authenticated", subscriptionId, "authenticated", null));
		webhook(RazorpayEvents.eventId(), RazorpayEvents.subscriptionEvent("subscription.activated", subscriptionId, "active", FIRST_CYCLE_END));
		webhook(RazorpayEvents.eventId(), RazorpayEvents.subscriptionCharged(subscriptionId, FakeRazorpay.paymentId(), 70000, FIRST_CYCLE_END));
		// a late, out-of-order authenticated event does not pull it back
		webhook(RazorpayEvents.eventId(), RazorpayEvents.subscriptionEvent("subscription.authenticated", subscriptionId, "authenticated", null));

		assertThat(subscriptionStatus(user)).isEqualTo("ACTIVE");
		assertThat(expiry(user)).isEqualTo(FIRST_CYCLE_END);
	}

	@Test
	void pendingAndHaltedChangeTheStatusOnlyAndAHaltedSubscriptionNoLongerSuppressesTheWarning() throws Exception {
		User user = newUser();
		String subscriptionId = activeSubscription(user, FakeRazorpay.paymentId());
		clock.setTo(FIRST_CYCLE_END.minus(Duration.ofDays(1)));
		assertThat((String) json(getAs(user, "/api/v1/billing/status"), "$.state")).isEqualTo("ACTIVE"); // renews by itself

		webhook(RazorpayEvents.eventId(), RazorpayEvents.subscriptionEvent("subscription.pending", subscriptionId, "pending", FIRST_CYCLE_END));
		assertThat(subscriptionStatus(user)).isEqualTo("PENDING");
		assertThat(expiry(user)).isEqualTo(FIRST_CYCLE_END);
		assertThat((String) json(getAs(user, "/api/v1/billing/status"), "$.state")).isEqualTo("ACTIVE"); // still retrying

		webhook(RazorpayEvents.eventId(), RazorpayEvents.subscriptionEvent("subscription.halted", subscriptionId, "halted", FIRST_CYCLE_END));
		assertThat(subscriptionStatus(user)).isEqualTo("HALTED");
		assertThat(expiry(user)).isEqualTo(FIRST_CYCLE_END);
		MvcResult status = getAs(user, "/api/v1/billing/status");
		assertThat((String) json(status, "$.state")).isEqualTo("EXPIRING");
		assertThat((Boolean) json(status, "$.autoRenew.active")).isFalse();
		// halted is like no subscription: the user may pay by hand
		assertThat(postAs(user, "/api/v1/billing/orders", "{\"period\":\"MONTHLY\"}").getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void cancellingKeepsPremiumUntilTheEndAndThenAllowsAnotherPayment() throws Exception {
		User user = newUser();
		String subscriptionId = activeSubscription(user, FakeRazorpay.paymentId());

		MvcResult cancelled = postAs(user, "/api/v1/billing/subscriptions/cancel", "");

		assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
		assertThat((Boolean) json(cancelled, "$.autoRenew.cancelAtCycleEnd")).isTrue();
		assertThat((Object) json(cancelled, "$.autoRenew.nextChargeAt")).isNull();
		assertThat((String) json(cancelled, "$.effectivePlan")).isEqualTo("PREMIUM");
		RAZORPAY.server.verify(postRequestedFor(urlEqualTo("/v1/subscriptions/" + subscriptionId + "/cancel"))
				.withRequestBody(equalToJson("{\"cancel_at_cycle_end\":1}")));
		assertThat(expiry(user)).isEqualTo(FIRST_CYCLE_END);
		assertThat(postAs(user, "/api/v1/billing/subscriptions/cancel", "").getResponse().getStatus()).isEqualTo(409);

		clock.setTo(FIRST_CYCLE_END.minus(Duration.ofDays(2)));
		assertThat((String) json(getAs(user, "/api/v1/billing/status"), "$.state")).isEqualTo("EXPIRING");
		pay(user, "MONTHLY"); // renewing by hand is allowed again
		assertThat(expiry(user)).isEqualTo(Instant.parse("2027-01-20T10:00:00Z"));
	}

	@Test
	void anotherSubscriptionOrAnOrderWhileOneIsActiveIsAConflict() throws Exception {
		User user = newUser();
		activeSubscription(user, FakeRazorpay.paymentId());

		MvcResult second = postAs(user, "/api/v1/billing/subscriptions", "{\"period\":\"YEARLY\"}");
		MvcResult order = postAs(user, "/api/v1/billing/orders", "{\"period\":\"MONTHLY\"}");

		assertThat(second.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(second)).isEqualTo("SUBSCRIPTION_ACTIVE");
		assertThat(order.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(order)).isEqualTo("SUBSCRIPTION_ACTIVE");
		assertThat(RAZORPAY.orderRequests()).isEmpty();
		assertThat(code(postAs(newUser(), "/api/v1/billing/subscriptions/cancel", ""))).isEqualTo("NO_ACTIVE_SUBSCRIPTION");
	}

	@Test
	void anAbandonedCheckoutIsReplacedAndCancelledAtRazorpay() throws Exception {
		User user = newUser();
		String abandoned = subscribe(user, "MONTHLY");

		String fresh = subscribe(user, "MONTHLY");

		assertThat(fresh).isNotEqualTo(abandoned);
		assertThat(jdbc.queryForObject("SELECT razorpay_subscription_id FROM subscriptions WHERE user_id = ?", String.class, user.getId())).isEqualTo(fresh);
		RAZORPAY.server.verify(postRequestedFor(urlEqualTo("/v1/subscriptions/" + abandoned + "/cancel")));
	}

	@Test
	void eventsForASubscriptionWeDoNotHoldAreIgnored() throws Exception {
		assertThat(webhook(RazorpayEvents.eventId(), RazorpayEvents.subscriptionCharged("sub_NotOurs000001", FakeRazorpay.paymentId(), 70000,
				FIRST_CYCLE_END)).getResponse().getStatus()).isEqualTo(200);
		assertThat(webhook(RazorpayEvents.eventId(), RazorpayEvents.subscriptionEvent("subscription.halted", "sub_NotOurs000001", "halted", null))
				.getResponse().getStatus()).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE razorpay_subscription_id = 'sub_NotOurs000001'", Integer.class)).isZero();
	}

	@Test
	void aWrongSignatureOrSomeoneElsesSubscriptionIsAVerificationFailure() throws Exception {
		User owner = newUser();
		String subscriptionId = subscribe(owner, "MONTHLY");
		RAZORPAY.subscriptionReads(subscriptionId, "active", FIRST_CYCLE_END, 1);
		String paymentId = FakeRazorpay.paymentId();

		MvcResult foreign = verify(newUser(), subscriptionId, paymentId);
		// the order layout of the two ids is not the subscription's
		MvcResult swapped = postAs(owner, "/api/v1/billing/subscriptions/verify", """
				{"razorpaySubscriptionId":"%s","razorpayPaymentId":"%s","razorpaySignature":"%s"}"""
				.formatted(subscriptionId, paymentId, FakeRazorpay.orderSignature(subscriptionId, paymentId)));

		assertThat(code(foreign)).isEqualTo("PAYMENT_VERIFICATION_FAILED");
		assertThat(code(swapped)).isEqualTo("PAYMENT_VERIFICATION_FAILED");
		assertThat(reload(owner).getPlan()).isEqualTo(Plan.FREE);
	}

	// ---- account deletion ----------------------------------------------------------------------------

	private int deleteAccount(User user) throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/users/me/delete").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"irrelevant\"}")).andReturn().getResponse().getStatus();
	}

	@Test
	void deletingAnAccountCancelsItsSubscriptionAtRazorpayAtOnce() throws Exception {
		User user = newUser();
		String subscriptionId = activeSubscription(user, FakeRazorpay.paymentId());

		assertThat(deleteAccount(user)).isEqualTo(204);

		RAZORPAY.server.verify(postRequestedFor(urlEqualTo("/v1/subscriptions/" + subscriptionId + "/cancel"))
				.withRequestBody(equalToJson("{\"cancel_at_cycle_end\":0}")));
		assertThat(subscriptionStatus(user)).isEqualTo("CANCELLED");
	}

	@Test
	void theAccountIsDeletedEvenWhenRazorpayIsDownAndTheSweepCancelsLater() throws Exception {
		User user = newUser();
		String subscriptionId = activeSubscription(user, FakeRazorpay.paymentId());
		RAZORPAY.failEverything(503);

		assertThat(deleteAccount(user)).isEqualTo(204);

		assertThat(userService.findById(user.getId())).isEmpty();
		assertThat(subscriptionStatus(user)).isEqualTo("ACTIVE");
		RAZORPAY.reset();
		assertThat(subscriptions.cancelOrphans()).isGreaterThanOrEqualTo(1);
		assertThat(subscriptionStatus(user)).isEqualTo("CANCELLED");
		RAZORPAY.server.verify(postRequestedFor(urlEqualTo("/v1/subscriptions/" + subscriptionId + "/cancel")));
	}
}
