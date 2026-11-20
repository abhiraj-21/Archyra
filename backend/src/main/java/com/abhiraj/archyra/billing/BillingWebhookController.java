package com.abhiraj.archyra.billing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.common.BillingRuleException;
import com.abhiraj.archyra.common.RateLimitedException;
import com.abhiraj.archyra.security.ClientIpResolver;
import com.abhiraj.archyra.security.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/billing/webhook}: the one public billing route (no JWT, CSRF-exempt, both explicit in
 * {@code SecurityConfig}). In this order: the per-IP rate limit; the body read as raw bytes up to {@value #MAX_BODY_BYTES}
 * (more is refused); the {@code X-Razorpay-Signature} over exactly those bytes, before anything is parsed; then the event,
 * deduplicated by {@code X-Razorpay-Event-Id}. Every refusal is a bare {@code 400} that says nothing about why. Success is
 * {@code 200} at once (Razorpay waits about 5 seconds and retries for a day); a failure of our own is a {@code 500}, so
 * Razorpay retries. No body, header or signature is ever logged.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
class BillingWebhookController {

	static final int MAX_BODY_BYTES = 64 * 1024;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final BillingProperties properties;
	private final BillingWebhooks webhooks;
	private final RateLimiter rateLimiter;
	private final ClientIpResolver clientIps;

	@PostMapping("/webhook")
	ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
		RateLimiter.Decision decision = rateLimiter.tryConsume(RateLimiter.Rule.BILLING_WEBHOOK, clientIps.resolve(request));
		if (!decision.allowed()) {
			throw new RateLimitedException(decision.retryAfterSeconds());
		}
		if (!properties.enabled() || !properties.razorpay().hasWebhookSecret()) {
			throw BillingRuleException.unavailable();
		}
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			return ResponseEntity.badRequest().build();
		}
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(MAX_BODY_BYTES + 1);
		}
		if (body.length == 0 || body.length > MAX_BODY_BYTES) {
			return ResponseEntity.badRequest().build();
		}
		if (!PaymentSignatures.webhook(properties.razorpay().webhookSecret(), body, request.getHeader("X-Razorpay-Signature"))) {
			log.info("A webhook delivery without a valid signature was refused");
			return ResponseEntity.badRequest().build();
		}
		JsonNode event;
		try {
			event = JSON.readTree(new String(body, StandardCharsets.UTF_8));
		}
		catch (RuntimeException notJson) {
			return ResponseEntity.badRequest().build();
		}
		if (event == null || !event.isObject()) {
			return ResponseEntity.badRequest().build();
		}
		String type = event.path("event").asString("");
		BillingWebhooks.Outcome outcome = webhooks.handle(eventId(request.getHeader("X-Razorpay-Event-Id"), body), type, event);
		log.info("Webhook {} {}", type.matches("[a-z._]{1,60}") ? type : "(unknown)", outcome);
		return ResponseEntity.status(HttpStatus.OK).build();
	}

	/** Razorpay's event id; a delivery without one is keyed by the hash of its body, which a retry repeats exactly. */
	private static String eventId(String header, byte[] body) {
		if (header != null && header.matches("[A-Za-z0-9_\\-]{1,100}")) {
			return header;
		}
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)).substring(0, 64);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
