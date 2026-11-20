package com.abhiraj.archyra.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.abhiraj.archyra.mail.BillingMails;
import com.abhiraj.archyra.mail.RecordingEmailService;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;

/** One-time payments: Razorpay Orders, the checkout's verify call, and the order webhooks, which must extend once between them. */
class BillingOrderIntegrationTest extends BillingIntegrationSupport {

	// ---- plans and prices ----------------------------------------------------------------------

	@Test
	void thePlansCarryTheConfiguredPricesLimitsAndThePublicKeyOnly() throws Exception {
		MvcResult plans = getAs(newUser(), "/api/v1/billing/plans");

		assertThat(plans.getResponse().getStatus()).isEqualTo(200);
		assertThat((Boolean) json(plans, "$.enabled")).isTrue();
		assertThat((String) json(plans, "$.keyId")).isEqualTo(FakeRazorpay.KEY_ID);
		assertThat((String) json(plans, "$.currency")).isEqualTo("INR");
		assertThat((List<Integer>) json(plans, "$.prices[*].amountPaise")).containsExactly(70000, 700000);
		assertThat((List<String>) json(plans, "$.prices[*].display")).containsExactly("700 INR", "7000 INR");
		assertThat((Integer) json(plans, "$.free.repositoryAnalyses")).isEqualTo(100000); // application-test.properties
		assertThat((Integer) json(plans, "$.aiQuestionsPerMinute")).isPositive();
		assertThat((Integer) json(plans, "$.keepRepositories")).isEqualTo(5);
		assertThat((Integer) json(plans, "$.graceDays")).isEqualTo(3);
		assertThat(plans.getResponse().getContentAsString()).doesNotContain(FakeRazorpay.KEY_SECRET, FakeRazorpay.WEBHOOK_SECRET, FakeRazorpay.PLAN_MONTHLY);
	}

	// ---- create and verify ---------------------------------------------------------------------

	@Test
	void theOrderAmountComesFromConfigurationNeverFromTheClient() throws Exception {
		User user = newUser();

		MvcResult created = postAs(user, "/api/v1/billing/orders", "{\"period\":\"MONTHLY\",\"amount\":100,\"currency\":\"USD\"}");

		assertThat(created.getResponse().getStatus()).isEqualTo(200);
		assertThat((Integer) json(created, "$.amount")).isEqualTo(70000);
		assertThat((String) json(created, "$.currency")).isEqualTo("INR");
		assertThat((String) json(created, "$.keyId")).isEqualTo(FakeRazorpay.KEY_ID);
		String paymentId = json(created, "$.paymentId");
		RAZORPAY.server.verify(postRequestedFor(urlEqualTo("/v1/orders")).withRequestBody(equalToJson(
				"{\"amount\":70000,\"currency\":\"INR\",\"receipt\":\"" + paymentId + "\",\"notes\":{\"paymentId\":\"" + paymentId + "\"}}")));
		// never an email or a name in what Razorpay is sent
		assertThat(RAZORPAY.orderRequests().get(0).getBodyAsString()).doesNotContain(user.getEmail(), user.getName());
		assertThat(paymentRows(user, "CREATED")).isEqualTo(1);
	}

	@Test
	void verifyingAMonthlyPaymentStartsPremiumForOneCalendarMonth() throws Exception {
		User user = newUser();
		String orderId = createOrder(user, "MONTHLY");

		MvcResult verified = verifyOrder(user, orderId, FakeRazorpay.paymentId());

		assertThat(verified.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) json(verified, "$.effectivePlan")).isEqualTo("PREMIUM");
		assertThat((String) json(verified, "$.state")).isEqualTo("ACTIVE");
		assertThat(reload(user).getPlan()).isEqualTo(Plan.PREMIUM);
		assertThat(expiry(user)).isEqualTo(Instant.parse("2026-12-20T10:00:00Z"));
		assertThat(paymentRows(user, "PAID")).isEqualTo(1);
		RecordingEmailService.BillingSent sent = mail.billingTo(user.getEmail(), "PREMIUM_ACTIVATED").getFirst();
		BillingMails.PremiumActivated activated = (BillingMails.PremiumActivated) sent.mail();
		assertThat(activated.expiresAt()).isEqualTo(Instant.parse("2026-12-20T10:00:00Z"));
		assertThat(activated.amount()).isEqualTo("700 INR");
		assertThat(activated.autoRenew()).isFalse();
	}

	@Test
	void anAnnualPaymentAddsOneCalendarYear() throws Exception {
		User user = newUser();

		pay(user, "YEARLY");

		assertThat(expiry(user)).isEqualTo(Instant.parse("2027-11-20T10:00:00Z"));
	}

	@Test
	void renewingEarlyKeepsTheDaysLeftAndRenewingLateStartsFromNow() throws Exception {
		User early = premiumUntil(NOW.plus(Duration.ofDays(10)));
		pay(early, "MONTHLY");
		// 30 November 2026 15:30 IST plus one month
		assertThat(expiry(early)).isEqualTo(Instant.parse("2026-12-30T10:00:00Z"));

		User late = premiumUntil(NOW.minus(Duration.ofDays(2)));
		pay(late, "MONTHLY");
		assertThat(expiry(late)).isEqualTo(Instant.parse("2026-12-20T10:00:00Z"));
	}

	@Test
	void theMonthEndAndLeapYearFollowTheKolkataCalendar() throws Exception {
		clock.setTo(Instant.parse("2028-01-31T04:30:00Z")); // 31 January 2028, 10:00 IST
		User user = newUser();

		pay(user, "MONTHLY");

		assertThat(expiry(user)).isEqualTo(Instant.parse("2028-02-29T04:30:00Z"));
		pay(user, "MONTHLY"); // from 29 February: 29 March
		assertThat(expiry(user)).isEqualTo(Instant.parse("2028-03-29T04:30:00Z"));
	}

	// ---- exactly once ----------------------------------------------------------------------------

	@Test
	void verifyThenBothOrderWebhooksDeliveredTwiceExtendExactlyOnce() throws Exception {
		User user = newUser();
		String orderId = createOrder(user, "MONTHLY");
		String paymentId = FakeRazorpay.paymentId();

		assertThat(verifyOrder(user, orderId, paymentId).getResponse().getStatus()).isEqualTo(200);
		String captured = RazorpayEvents.paymentCaptured(paymentId, orderId, 70000, "INR");
		String paid = RazorpayEvents.orderPaid(paymentId, orderId, 70000, "INR");
		String capturedId = RazorpayEvents.eventId();
		String paidId = RazorpayEvents.eventId();
		for (int delivery = 0; delivery < 2; delivery++) {
			assertThat(webhook(capturedId, captured).getResponse().getStatus()).isEqualTo(200);
			assertThat(webhook(paidId, paid).getResponse().getStatus()).isEqualTo(200);
		}
		// a second verify of the same checkout is a no-op too
		assertThat(verifyOrder(user, orderId, paymentId).getResponse().getStatus()).isEqualTo(200);

		assertThat(expiry(user)).isEqualTo(Instant.parse("2026-12-20T10:00:00Z"));
		assertThat(paymentRows(user, "PAID")).isEqualTo(1);
		assertThat(mail.billingTo(user.getEmail(), "PREMIUM_ACTIVATED")).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id IN (?, ?)", Integer.class, capturedId, paidId)).isEqualTo(2);
	}

	@Test
	void theWebhookAloneIsEnoughAndAReplayedEventIdIsANoOp() throws Exception {
		User user = newUser();
		String orderId = createOrder(user, "YEARLY");
		String paymentId = FakeRazorpay.paymentId();
		String eventId = RazorpayEvents.eventId();

		assertThat(webhook(eventId, RazorpayEvents.paymentCaptured(paymentId, orderId, 700000, "INR")).getResponse().getStatus()).isEqualTo(200);
		Instant first = expiry(user);
		assertThat(first).isEqualTo(Instant.parse("2027-11-20T10:00:00Z"));

		// the same event id with another body (a replay) does nothing, even for a payment that has not been applied
		String otherOrder = createOrder(user, "MONTHLY");
		assertThat(webhook(eventId, RazorpayEvents.paymentCaptured(FakeRazorpay.paymentId(), otherOrder, 70000, "INR")).getResponse().getStatus())
				.isEqualTo(200);
		assertThat(expiry(user)).isEqualTo(first);
		assertThat(paymentRows(user, "CREATED")).isEqualTo(1);
	}

	@Test
	void aConcurrentVerifyAndWebhookForOnePaymentExtendOnce() throws Exception {
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 5; round++) {
				User user = newUser();
				String orderId = createOrder(user, "MONTHLY");
				String paymentId = FakeRazorpay.paymentId();
				String body = RazorpayEvents.paymentCaptured(paymentId, orderId, 70000, "INR");
				CyclicBarrier start = new CyclicBarrier(2);
				List<Callable<Integer>> both = List.of(() -> {
					start.await(10, TimeUnit.SECONDS);
					return verifyOrder(user, orderId, paymentId).getResponse().getStatus();
				}, () -> {
					start.await(10, TimeUnit.SECONDS);
					return webhook(RazorpayEvents.eventId(), body).getResponse().getStatus();
				});
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> result : threads.invokeAll(both)) {
					statuses.add(result.get());
				}
				assertThat(statuses).containsOnly(200);
				assertThat(expiry(user)).as("round " + round).isEqualTo(Instant.parse("2026-12-20T10:00:00Z"));
				assertThat(mail.billingTo(user.getEmail(), "PREMIUM_ACTIVATED")).hasSize(1);
			}
		}
		finally {
			threads.shutdownNow();
		}
	}

	@Test
	void aFailedAttemptFollowedByASuccessOnTheSameOrderStillExtends() throws Exception {
		User user = newUser();
		String orderId = createOrder(user, "MONTHLY");

		assertThat(webhook(RazorpayEvents.eventId(), RazorpayEvents.paymentFailed(FakeRazorpay.paymentId(), orderId, 70000)).getResponse().getStatus())
				.isEqualTo(200);
		assertThat(paymentRows(user, "FAILED")).isEqualTo(1);
		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE);

		pay(user, "MONTHLY"); // a new order works too
		assertThat(verifyOrder(user, orderId, FakeRazorpay.paymentId()).getResponse().getStatus()).isEqualTo(200); // the retried attempt
		assertThat(expiry(user)).isEqualTo(Instant.parse("2027-01-20T10:00:00Z"));
	}

	// ---- refusals ----------------------------------------------------------------------------------

	@Test
	void someoneElsesOrderAWrongSignatureAndAnUnknownOrderAreVerificationFailures() throws Exception {
		User owner = newUser();
		User other = newUser();
		String orderId = createOrder(owner, "MONTHLY");
		String paymentId = FakeRazorpay.paymentId();

		MvcResult foreign = verifyOrder(other, orderId, paymentId);
		MvcResult wrong = postAs(owner, "/api/v1/billing/orders/verify", """
				{"razorpayOrderId":"%s","razorpayPaymentId":"%s","razorpaySignature":"%s"}"""
				.formatted(orderId, paymentId, FakeRazorpay.orderSignature(orderId, "pay_Other")));
		MvcResult unknown = verifyOrder(owner, "order_NotOurs00001", paymentId);
		MvcResult malformed = postAs(owner, "/api/v1/billing/orders/verify", "{\"razorpayOrderId\":\"../x\",\"razorpayPaymentId\":\"p\",\"razorpaySignature\":\"zz\"}");

		for (MvcResult result : List.of(foreign, wrong, unknown, malformed)) {
			assertThat(result.getResponse().getStatus()).isEqualTo(400);
			assertThat(code(result)).isEqualTo("PAYMENT_VERIFICATION_FAILED");
		}
		assertThat(reload(owner).getPlan()).isEqualTo(Plan.FREE);
		assertThat(reload(other).getPlan()).isEqualTo(Plan.FREE);
		assertThat(paymentRows(owner, "CREATED")).isEqualTo(1);
	}

	@Test
	void aConfirmationWithAnotherAmountOrCurrencyNeverExtends() throws Exception {
		User user = newUser();
		String orderId = createOrder(user, "YEARLY");

		assertThat(webhook(RazorpayEvents.eventId(), RazorpayEvents.paymentCaptured(FakeRazorpay.paymentId(), orderId, 70000, "INR"))
				.getResponse().getStatus()).isEqualTo(200);
		assertThat(webhook(RazorpayEvents.eventId(), RazorpayEvents.orderPaid(FakeRazorpay.paymentId(), orderId, 700000, "USD"))
				.getResponse().getStatus()).isEqualTo(200);

		assertThat(reload(user).getPlan()).isEqualTo(Plan.FREE);
		assertThat(paymentRows(user, "CREATED")).isEqualTo(1);
	}

	@Test
	void anInvalidPeriodIsRefused() throws Exception {
		User user = newUser();
		for (String body : List.of("{\"period\":\"WEEKLY\"}", "{}", "{\"period\":null}", "")) {
			MvcResult result = postAs(user, "/api/v1/billing/orders", body);
			assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
			assertThat(code(result)).isEqualTo("INVALID_PERIOD");
		}
		assertThat(RAZORPAY.orderRequests()).isEmpty();
	}

	@Test
	void razorpayDownIsBillingUnavailableAndNothingIsRecorded() throws Exception {
		User user = newUser();
		RAZORPAY.failEverything(503);

		MvcResult result = postAs(user, "/api/v1/billing/orders", "{\"period\":\"MONTHLY\"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(code(result)).isEqualTo("BILLING_UNAVAILABLE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE user_id = ?", Integer.class, user.getId())).isZero();
	}

	@Test
	void everyBillingRouteButTheWebhookNeedsAToken() throws Exception {
		for (String path : List.of("/api/v1/billing/plans", "/api/v1/billing/status", "/api/v1/billing/retention", "/api/v1/billing/payments")) {
			assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn().getResponse().getStatus())
					.as(path).isEqualTo(401);
		}
		assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/billing/orders")
				.contentType("application/json").content("{\"period\":\"MONTHLY\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	// ---- history and status -------------------------------------------------------------------------

	@Test
	void theHistoryListsTheCallersPaymentsNewestFirst() throws Exception {
		User user = newUser();
		pay(user, "MONTHLY");
		clock.advance(Duration.ofMinutes(1));
		pay(user, "YEARLY");
		createOrder(newUser(), "MONTHLY"); // someone else's

		MvcResult history = getAs(user, "/api/v1/billing/payments?size=10");

		assertThat((List<String>) json(history, "$.items[*].period")).containsExactly("YEARLY", "MONTHLY");
		assertThat((List<String>) json(history, "$.items[*].status")).containsOnly("PAID");
		assertThat((Integer) json(history, "$.totalElements")).isEqualTo(2);
		assertThat(getAs(user, "/api/v1/billing/payments?size=0").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void theStatusSaysNoneActiveExpiringGraceAndLapsedAtTheBoundaries() throws Exception {
		assertThat((String) json(getAs(newUser(), "/api/v1/billing/status"), "$.state")).isEqualTo("NONE");

		// three days before the end counts as within the reminder days; a second more does not
		User exactly = premiumUntil(NOW.plus(Duration.ofDays(3)));
		User justBefore = premiumUntil(NOW.plus(Duration.ofDays(3)).plusSeconds(1));
		assertThat((String) json(getAs(exactly, "/api/v1/billing/status"), "$.state")).isEqualTo("EXPIRING");
		assertThat((String) json(getAs(justBefore, "/api/v1/billing/status"), "$.state")).isEqualTo("ACTIVE");

		MvcResult expiring = getAs(exactly, "/api/v1/billing/status");
		assertThat((String) json(expiring, "$.grace.endsAt")).isEqualTo("2026-11-26T10:00:00Z");
		assertThat((Integer) json(expiring, "$.grace.repositoriesAtRisk")).isZero();

		// the end itself: Premium is over (effective FREE), the repositories are still there
		User ended = premiumUntil(NOW);
		MvcResult grace = getAs(ended, "/api/v1/billing/status");
		assertThat((String) json(grace, "$.state")).isEqualTo("GRACE");
		assertThat((String) json(grace, "$.effectivePlan")).isEqualTo("FREE");
		assertThat((String) json(grace, "$.plan")).isEqualTo("PREMIUM");
		assertThat((String) json(grace, "$.planExpiresAt")).isEqualTo("2026-11-20T10:00:00Z");

		User noEnd = premiumUntil(null);
		assertThat((String) json(getAs(noEnd, "/api/v1/billing/status"), "$.state")).isEqualTo("ACTIVE");

		// a FREE user who paid once is LAPSED, not NONE
		User paid = newUser();
		pay(paid, "MONTHLY");
		User back = reload(paid);
		back.changePlan(Plan.FREE, null);
		userService.save(back);
		assertThat((String) json(getAs(paid, "/api/v1/billing/status"), "$.state")).isEqualTo("LAPSED");
	}
}
