package com.abhiraj.archyra.billing;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One payment ({@code payments}): a one-time order, or one charge of a subscription. The id is ours and is also the Razorpay
 * {@code receipt} (and a note) of an order. The amount and currency are what we asked for; a confirmation that reports
 * anything else is ignored. The status moves to {@code PAID} only through the guarded UPDATE in
 * {@link PaymentRepository#markPaid}, which is what makes Premium extend once per payment. Holds a plain user id (no
 * reference into {@code user/}) and nothing personal.
 */
@Entity
@DynamicUpdate
@Table(name = "payments", indexes = @Index(name = "idx_payments_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Payment {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private PaymentMode mode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private BillingPeriod period;

	@Column(name = "amount_paise", nullable = false, updatable = false)
	private long amountPaise;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PaymentStatus status;

	@Column(name = "razorpay_order_id", unique = true, length = 64)
	private String razorpayOrderId;

	@Column(name = "razorpay_subscription_id", length = 64)
	private String razorpaySubscriptionId;

	@Column(name = "razorpay_payment_id", unique = true, length = 64)
	private String razorpayPaymentId;

	@Column(name = "paid_at")
	private Instant paidAt;

	@Column(name = "extended_from")
	private Instant extendedFrom;

	@Column(name = "extended_to")
	private Instant extendedTo;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private Payment(UUID id, UUID userId, PaymentMode mode, BillingPeriod period, long amountPaise, String currency, Instant now) {
		this.id = id;
		this.userId = userId;
		this.mode = mode;
		this.period = period;
		this.amountPaise = amountPaise;
		this.currency = currency;
		this.status = PaymentStatus.CREATED;
		this.createdAt = now;
	}

	/** A one-time order: the row is written once Razorpay has created the order, with its id. */
	static Payment forOrder(UUID id, UUID userId, BillingPeriod period, long amountPaise, String currency, String razorpayOrderId, Instant now) {
		Payment payment = new Payment(id, userId, PaymentMode.ORDER, period, amountPaise, currency, now);
		payment.razorpayOrderId = razorpayOrderId;
		return payment;
	}
}
