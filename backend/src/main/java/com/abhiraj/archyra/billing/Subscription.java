package com.abhiraj.archyra.billing;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A user's auto-renewing Razorpay Subscription ({@code subscriptions}), one row per user: starting a new one replaces the
 * row. Webhook events are matched to the user only through {@code razorpay_subscription_id} here, never through anything
 * in the payload alone.
 */
@Entity
@DynamicUpdate
@Table(name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Subscription {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, unique = true, updatable = false)
	private UUID userId;

	@Column(name = "razorpay_subscription_id", nullable = false, unique = true, length = 64)
	private String razorpaySubscriptionId;

	@Column(name = "razorpay_plan_id", nullable = false, length = 64)
	private String razorpayPlanId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private BillingPeriod period;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 15)
	private SubscriptionStatus status;

	@Column(name = "cancel_at_cycle_end", nullable = false)
	private boolean cancelAtCycleEnd;

	/** The end of the current cycle as Razorpay reports it; the next charge is due then. */
	@Column(name = "current_end")
	private Instant currentEnd;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	Subscription(UUID userId, String razorpaySubscriptionId, String razorpayPlanId, BillingPeriod period, Instant now) {
		this.userId = userId;
		replaceWith(razorpaySubscriptionId, razorpayPlanId, period, now);
	}

	/** A new Razorpay subscription takes the user's row over. */
	void replaceWith(String razorpaySubscriptionId, String razorpayPlanId, BillingPeriod period, Instant now) {
		this.razorpaySubscriptionId = razorpaySubscriptionId;
		this.razorpayPlanId = razorpayPlanId;
		this.period = period;
		this.status = SubscriptionStatus.CREATED;
		this.cancelAtCycleEnd = false;
		this.currentEnd = null;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Out-of-order events cannot move it backwards ({@link SubscriptionStatus#then}). */
	void moveTo(SubscriptionStatus next, Instant currentEnd, Instant now) {
		this.status = status.then(next);
		if (currentEnd != null) {
			this.currentEnd = currentEnd;
		}
		this.updatedAt = now;
	}

	void cancelAtCycleEnd(Instant now) {
		this.cancelAtCycleEnd = true;
		this.updatedAt = now;
	}

	/** Live and not cancelled: it will charge again, so no reminder is needed and nothing else may be bought. */
	boolean autoRenews() {
		return status.live() && !cancelAtCycleEnd;
	}
}
