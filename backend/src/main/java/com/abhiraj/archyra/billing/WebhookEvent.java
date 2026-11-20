package com.abhiraj.archyra.billing;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A Razorpay webhook delivery that was processed ({@code webhook_events}). Inserted in the same transaction as the work, so a
 * repeated delivery (Razorpay retries for a day) is a no-op, and a delivery whose work failed can be retried. No payload.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WebhookEvent {

	@Id
	@Column(name = "event_id", length = 100)
	private String eventId;

	@Column(nullable = false, length = 60)
	private String type;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	@Column(name = "processed_at")
	private Instant processedAt;
}
