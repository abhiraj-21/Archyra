package com.abhiraj.archyra.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ai.llm.LogCapture;

/** The REST gateway against the WireMock fake of Razorpay: wire format, HTTP Basic, failures without bodies. */
class RazorpayRestGatewayTest {

	static FakeRazorpay razorpay;
	RazorpayRestGateway gateway;

	@BeforeAll
	static void startFake() {
		razorpay = new FakeRazorpay().start();
	}

	@AfterAll
	static void stopFake() {
		razorpay.stop();
	}

	@BeforeEach
	void setUp() {
		razorpay.reset();
		gateway = new RazorpayRestGateway(BillingPropertiesTest.properties(BillingPropertiesTest.razorpay(razorpay.baseUrl())));
	}

	@Test
	void anOrderIsCreatedWithTheAmountReceiptAndNotesWeGiveAndHttpBasic() {
		RazorpayGateway.Order order = gateway.createOrder(70000, "INR", "6f1c0000-0000-0000-0000-000000000001",
				Map.of("paymentId", "6f1c0000-0000-0000-0000-000000000001"));

		assertThat(order.id()).startsWith("order_");
		assertThat(order.amount()).isEqualTo(70000);
		assertThat(order.currency()).isEqualTo("INR");
		assertThat(order.receipt()).isEqualTo("6f1c0000-0000-0000-0000-000000000001");
		assertThat(order.status()).isEqualTo("created");
		razorpay.server.verify(postRequestedFor(urlEqualTo("/v1/orders")).withHeader("Authorization", FakeRazorpay.basicAuthHeader())
				.withRequestBody(equalToJson("""
						{"amount":70000,"currency":"INR","receipt":"6f1c0000-0000-0000-0000-000000000001",
						 "notes":{"paymentId":"6f1c0000-0000-0000-0000-000000000001"}}""")));
	}

	@Test
	void aSubscriptionIsCreatedForThePlanWithItsCycleCount() {
		RazorpayGateway.RemoteSubscription subscription = gateway.createSubscription(FakeRazorpay.PLAN_YEARLY, 10, Map.of("userRef", "x"));

		assertThat(subscription.id()).startsWith("sub_");
		assertThat(subscription.planId()).isEqualTo(FakeRazorpay.PLAN_YEARLY);
		assertThat(subscription.status()).isEqualTo("created");
		assertThat(subscription.currentEnd()).isNull();
		razorpay.server.verify(postRequestedFor(urlEqualTo("/v1/subscriptions")).withHeader("Authorization", FakeRazorpay.basicAuthHeader())
				.withRequestBody(equalToJson("{\"plan_id\":\"" + FakeRazorpay.PLAN_YEARLY + "\",\"total_count\":10,\"quantity\":1,\"customer_notify\":1,\"notes\":{\"userRef\":\"x\"}}")));
	}

	@Test
	void cancellingAtTheCycleEndKeepsItActiveAndAtOnceCancelsIt() {
		assertThat(gateway.cancelSubscription("sub_Abc123", true).status()).isEqualTo("active");
		assertThat(gateway.cancelSubscription("sub_Abc123", false).status()).isEqualTo("cancelled");
		razorpay.server.verify(postRequestedFor(urlEqualTo("/v1/subscriptions/sub_Abc123/cancel")).withRequestBody(equalToJson("{\"cancel_at_cycle_end\":1}")));
	}

	@Test
	void aFetchedSubscriptionCarriesItsCycleEndInEpochSeconds() {
		razorpay.subscriptionReads("sub_Fetch1", "active", Instant.parse("2026-12-20T10:00:00Z"), 1);

		RazorpayGateway.RemoteSubscription subscription = gateway.fetchSubscription("sub_Fetch1");

		assertThat(subscription.status()).isEqualTo("active");
		assertThat(subscription.currentEnd()).isEqualTo(Instant.parse("2026-12-20T10:00:00Z"));
		assertThat(subscription.paidCount()).isEqualTo(1);
		razorpay.server.verify(getRequestedFor(urlEqualTo("/v1/subscriptions/sub_Fetch1")).withHeader("Authorization", equalTo(FakeRazorpay.basicAuth())));
	}

	@Test
	void anErrorStatusIsAFailureThatCarriesNeitherTheBodyNorTheKeys() {
		razorpay.failEverything(500);

		try (LogCapture logs = new LogCapture()) {
			assertThatThrownBy(() -> gateway.createOrder(70000, "INR", "r", Map.of())).isInstanceOfSatisfying(RazorpayUnavailableException.class, ex -> {
				assertThat(ex.status()).isEqualTo(500);
				assertThat(ex.getMessage()).doesNotContain("secret-detail-from-razorpay", FakeRazorpay.KEY_SECRET, FakeRazorpay.KEY_ID);
				assertThat(ex.getCause()).isNull();
			});
			assertThat(String.join("\n", logs.texts())).doesNotContain("secret-detail-from-razorpay", FakeRazorpay.KEY_SECRET);
		}
		razorpay.failEverything(401);
		assertThatThrownBy(() -> gateway.fetchSubscription("sub_X")).isInstanceOf(RazorpayUnavailableException.class);
	}

	@Test
	void anUnreachableServerOrAnIdThatIsNotRazorpaysIsAFailure() {
		RazorpayRestGateway down = new RazorpayRestGateway(BillingPropertiesTest.properties(BillingPropertiesTest.razorpay("http://127.0.0.1:1")));
		assertThatThrownBy(() -> down.createOrder(70000, "INR", "r", Map.of())).isInstanceOfSatisfying(RazorpayUnavailableException.class,
				ex -> assertThat(ex.getMessage()).doesNotContain("127.0.0.1"));
		assertThatThrownBy(() -> gateway.fetchSubscription("../orders")).isInstanceOf(RazorpayUnavailableException.class);
		assertThat(razorpay.server.getAllServeEvents()).isEmpty();
	}

	@Test
	void withoutKeysNothingIsCalled() {
		RazorpayRestGateway off = new RazorpayRestGateway(BillingPropertiesTest.properties(
				new BillingProperties.Razorpay("", "", "", "", "", razorpay.baseUrl(), java.time.Duration.ofSeconds(1))));
		assertThatThrownBy(() -> off.createOrder(70000, "INR", "r", Map.of())).isInstanceOf(RazorpayUnavailableException.class);
		assertThat(razorpay.server.getAllServeEvents()).isEmpty();
	}
}
