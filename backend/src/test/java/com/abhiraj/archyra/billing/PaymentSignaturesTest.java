package com.abhiraj.archyra.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The three Razorpay signatures with real HMACs. The expected values were computed independently
 * ({@code printf '%s' 'order_1|pay_1' | openssl dgst -sha256 -hmac secret}), so a wrong message layout fails here.
 */
class PaymentSignaturesTest {

	private static final String SECRET = "secret";
	/** HMAC-SHA256("secret", "order_1|pay_1"). */
	private static final String ORDER_SIGNATURE = "52115a0d3400de9e86aade1f1b6eba9e8974604f4e267a9e9a16633a4c8dd2cb";

	@Test
	void anOrderCheckoutSignatureIsOrderIdBarPaymentId() {
		String signature = PaymentSignatures.sign(SECRET, "order_1|pay_1".getBytes(StandardCharsets.UTF_8));

		assertThat(PaymentSignatures.orderCheckout(SECRET, "order_1", "pay_1", signature)).isTrue();
		assertThat(PaymentSignatures.orderCheckout(SECRET, "order_1", "pay_1", signature.toUpperCase())).isTrue();
		// the other order of the two ids is the subscription layout, and must not pass for an order
		assertThat(PaymentSignatures.orderCheckout(SECRET, "pay_1", "order_1", signature)).isFalse();
		assertThat(PaymentSignatures.orderCheckout(SECRET, "order_2", "pay_1", signature)).isFalse();
		assertThat(PaymentSignatures.orderCheckout("other-secret", "order_1", "pay_1", signature)).isFalse();
	}

	@Test
	void aSubscriptionCheckoutSignatureIsPaymentIdBarSubscriptionId() {
		String signature = PaymentSignatures.sign(SECRET, "pay_1|sub_1".getBytes(StandardCharsets.UTF_8));

		assertThat(PaymentSignatures.subscriptionCheckout(SECRET, "pay_1", "sub_1", signature)).isTrue();
		assertThat(PaymentSignatures.subscriptionCheckout(SECRET, "sub_1", "pay_1", signature)).isFalse();
		assertThat(PaymentSignatures.subscriptionCheckout(SECRET, "pay_2", "sub_1", signature)).isFalse();
	}

	@Test
	void theSignatureMatchesAnIndependentlyComputedValue() {
		assertThat(PaymentSignatures.sign(SECRET, "order_1|pay_1".getBytes(StandardCharsets.UTF_8))).hasSize(64).matches("[0-9a-f]{64}");
		// recomputed with the JDK only, to pin the algorithm and the hex encoding
		assertThat(PaymentSignatures.sign(SECRET, "order_1|pay_1".getBytes(StandardCharsets.UTF_8))).isEqualTo(jdkHmac(SECRET, "order_1|pay_1"));
		assertThat(PaymentSignatures.orderCheckout(SECRET, "order_1", "pay_1", ORDER_SIGNATURE)).isTrue(); // openssl dgst -sha256 -hmac secret
	}

	@Test
	void aWebhookSignatureCoversTheRawBodyBytes() {
		String body = "{\"event\":\"payment.captured\",\"payload\":{}}";
		String signature = PaymentSignatures.sign("whsec", body.getBytes(StandardCharsets.UTF_8));

		assertThat(PaymentSignatures.webhook("whsec", body.getBytes(StandardCharsets.UTF_8), signature)).isTrue();
		// one changed byte, or the same JSON with different whitespace, is a different body
		assertThat(PaymentSignatures.webhook("whsec", body.replace("captured", "capturex").getBytes(StandardCharsets.UTF_8), signature)).isFalse();
		assertThat(PaymentSignatures.webhook("whsec", body.replace(",", ", ").getBytes(StandardCharsets.UTF_8), signature)).isFalse();
		assertThat(PaymentSignatures.webhook("other", body.getBytes(StandardCharsets.UTF_8), signature)).isFalse();
	}

	@Test
	void aMissingBlankOrMalformedSignatureIsFalseWithoutThrowing() {
		byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
		assertThat(PaymentSignatures.webhook("whsec", body, null)).isFalse();
		assertThat(PaymentSignatures.webhook("whsec", body, "")).isFalse();
		assertThat(PaymentSignatures.webhook("whsec", body, "abc")).isFalse();
		assertThat(PaymentSignatures.webhook("whsec", body, "z".repeat(64))).isFalse();
		assertThat(PaymentSignatures.webhook("whsec", null, "a".repeat(64))).isFalse();
		// no secret configured: nothing passes, not even a signature made with an empty key
		assertThat(PaymentSignatures.webhook("", body, PaymentSignatures.sign("x", body))).isFalse();
		assertThat(PaymentSignatures.webhook(null, body, "a".repeat(64))).isFalse();
		assertThat(PaymentSignatures.orderCheckout(SECRET, "", "pay_1", "a".repeat(64))).isFalse();
		assertThat(PaymentSignatures.orderCheckout(SECRET, "order_1", null, "a".repeat(64))).isFalse();
		assertThat(PaymentSignatures.subscriptionCheckout(SECRET, "pay_1", " ", "a".repeat(64))).isFalse();
	}

	@Test
	void theComparisonIsMessageDigestIsEqual() {
		byte[] a = { 1, 2, 3 };
		assertThat(PaymentSignatures.constantTimeEquals(a, new byte[] { 1, 2, 3 })).isTrue();
		assertThat(PaymentSignatures.constantTimeEquals(a, new byte[] { 1, 2, 4 })).isFalse();
		assertThat(PaymentSignatures.constantTimeEquals(a, new byte[] { 1, 2 })).isFalse();
		assertThat(PaymentSignatures.constantTimeEquals(a, new byte[0])).isFalse();
	}

	private static String jdkHmac(String secret, String message) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return java.util.HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
