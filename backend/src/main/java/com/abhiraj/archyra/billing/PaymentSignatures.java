package com.abhiraj.archyra.billing;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The HMAC-SHA256 checks Razorpay defines, as lower-case hex, compared in constant time ({@link MessageDigest#isEqual}).
 * Pure: no Spring, no logging. A missing, blank, malformed (not 64 hex characters) or wrong signature is simply
 * {@code false}, before anything else is looked at.
 * <ul>
 * <li>order checkout: {@code HMAC(key_secret, orderId + "|" + paymentId)}</li>
 * <li>subscription checkout: {@code HMAC(key_secret, paymentId + "|" + subscriptionId)}</li>
 * <li>webhook: {@code HMAC(webhook_secret, raw body bytes)} against {@code X-Razorpay-Signature}</li>
 * </ul>
 */
final class PaymentSignatures {

	private static final String ALGORITHM = "HmacSHA256";
	private static final int SIGNATURE_HEX_LENGTH = 64;

	private PaymentSignatures() {
	}

	static boolean orderCheckout(String keySecret, String orderId, String paymentId, String signature) {
		if (blank(orderId) || blank(paymentId)) {
			return false;
		}
		return matches(keySecret, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), signature);
	}

	static boolean subscriptionCheckout(String keySecret, String paymentId, String subscriptionId, String signature) {
		if (blank(paymentId) || blank(subscriptionId)) {
			return false;
		}
		return matches(keySecret, (paymentId + "|" + subscriptionId).getBytes(StandardCharsets.UTF_8), signature);
	}

	/** Over the bytes exactly as received: never a re-serialised body. */
	static boolean webhook(String webhookSecret, byte[] rawBody, String signature) {
		if (rawBody == null) {
			return false;
		}
		return matches(webhookSecret, rawBody, signature);
	}

	/** The signature Razorpay would send; tests and the fake gateway use it to sign what they send. */
	static String sign(String secret, byte[] message) {
		return HexFormat.of().formatHex(hmac(secret, message));
	}

	/** Constant time in the length of the expected value: both sides are always 32 bytes when compared. */
	static boolean constantTimeEquals(byte[] expected, byte[] given) {
		return MessageDigest.isEqual(expected, given);
	}

	private static boolean matches(String secret, byte[] message, String signature) {
		if (blank(secret) || signature == null || signature.length() != SIGNATURE_HEX_LENGTH) {
			return false;
		}
		byte[] given;
		try {
			given = HexFormat.of().parseHex(signature.toLowerCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
		return constantTimeEquals(hmac(secret, message), given);
	}

	private static byte[] hmac(String secret, byte[] message) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
			return mac.doFinal(message);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HmacSHA256 is not available", ex);
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
