package com.abhiraj.archyra.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * A fake of the parts of the Razorpay REST API that Archyra calls, speaking its real wire format (JSON bodies, lower-case
 * statuses, epoch seconds, HTTP Basic with the key id and secret). Ids are random like Razorpay's ({@code order_...},
 * {@code sub_...}); a test reads them from Archyra's response. Also signs what the browser checkout and the webhook would
 * send, with the same test secrets the application is given.
 */
final class FakeRazorpay {

	static final String KEY_ID = "rzp_test_FakeKeyId0001";
	static final String KEY_SECRET = "fake-key-secret-for-tests-only";
	static final String WEBHOOK_SECRET = "fake-webhook-secret-for-tests-only";
	static final String PLAN_MONTHLY = "plan_FakeMonthly0001";
	static final String PLAN_YEARLY = "plan_FakeYearly00001";

	final WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort()
			.globalTemplating(true));

	FakeRazorpay start() {
		server.start();
		reset();
		return this;
	}

	void stop() {
		server.stop();
	}

	String baseUrl() {
		return server.baseUrl();
	}

	/** The default behaviour: orders and subscriptions are created; a subscription reads as {@code created}. */
	void reset() {
		server.resetAll();
		server.stubFor(post(urlEqualTo("/v1/orders")).atPriority(10).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("""
						{"id":"order_{{randomValue length=14 type='ALPHANUMERIC'}}","entity":"order",
						 "amount":{{jsonPath request.body '$.amount'}},"amount_paid":0,"amount_due":{{jsonPath request.body '$.amount'}},
						 "currency":"{{jsonPath request.body '$.currency'}}","receipt":"{{jsonPath request.body '$.receipt'}}",
						 "status":"created","attempts":0,"notes":{"paymentId":"{{jsonPath request.body '$.notes.paymentId'}}"},"created_at":1763600000}""")));
		server.stubFor(post(urlEqualTo("/v1/subscriptions")).atPriority(10).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("""
						{"id":"sub_{{randomValue length=14 type='ALPHANUMERIC'}}","entity":"subscription",
						 "plan_id":"{{jsonPath request.body '$.plan_id'}}","status":"created","current_start":null,"current_end":null,
						 "quantity":1,"total_count":{{jsonPath request.body '$.total_count'}},"paid_count":0,
						 "short_url":"https://rzp.io/i/fake","created_at":1763600000}""")));
		for (boolean atCycleEnd : new boolean[] { true, false }) {
			server.stubFor(post(urlPathMatching("/v1/subscriptions/sub_[A-Za-z0-9]+/cancel")).atPriority(10)
					.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.cancel_at_cycle_end", equalTo(atCycleEnd ? "1" : "0")))
					.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
							{"id":"{{request.pathSegments.[2]}}","entity":"subscription","plan_id":"plan_x","status":"%s",
							 "current_end":null,"paid_count":1}""".formatted(atCycleEnd ? "active" : "cancelled"))));
		}
	}

	/** What {@code GET /v1/subscriptions/{id}} answers for one subscription. */
	void subscriptionReads(String subscriptionId, String status, Instant currentEnd, int paidCount) {
		server.stubFor(get(urlEqualTo("/v1/subscriptions/" + subscriptionId)).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("""
						{"id":"%s","entity":"subscription","plan_id":"%s","status":"%s","current_end":%s,"paid_count":%d}"""
						.formatted(subscriptionId, PLAN_MONTHLY, status, currentEnd == null ? "null" : String.valueOf(currentEnd.getEpochSecond()), paidCount))));
	}

	/** Every call answers with this status (Razorpay down, bad key ...). */
	void failEverything(int status) {
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.any(urlPathMatching("/v1/.*")).atPriority(1)
				.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
						.withBody("{\"error\":{\"code\":\"SERVER_ERROR\",\"description\":\"secret-detail-from-razorpay\"}}")));
	}

	List<LoggedRequest> orderRequests() {
		return server.findAll(postRequestedFor(urlEqualTo("/v1/orders")));
	}

	List<LoggedRequest> cancelRequests() {
		return server.findAll(postRequestedFor(urlPathMatching("/v1/subscriptions/.*/cancel")));
	}

	static String basicAuth() {
		return "Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
	}

	static com.github.tomakehurst.wiremock.matching.StringValuePattern basicAuthHeader() {
		return equalTo(basicAuth());
	}

	// ---- what the browser and the webhook send ---------------------------------------------------------------

	static String orderSignature(String orderId, String paymentId) {
		return PaymentSignatures.sign(KEY_SECRET, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
	}

	static String subscriptionSignature(String paymentId, String subscriptionId) {
		return PaymentSignatures.sign(KEY_SECRET, (paymentId + "|" + subscriptionId).getBytes(StandardCharsets.UTF_8));
	}

	static String webhookSignature(String body) {
		return PaymentSignatures.sign(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));
	}

	static String paymentId() {
		return "pay_" + random();
	}

	static String random() {
		return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 14);
	}
}
