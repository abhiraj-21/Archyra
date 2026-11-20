package com.abhiraj.archyra.billing;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One lifecycle step taken for one Premium end date ({@code billing_notices}). The unique {@code (user_id, kind,
 * period_end)} is what makes every reminder, expiry email and repository deletion happen once, however often and on however
 * many nodes the job runs: a step is claimed by inserting its row, and only the inserter acts. A renewal moves the end date,
 * so the next period gets its own rows.
 */
@Entity
@Table(name = "billing_notices", uniqueConstraints = @UniqueConstraint(name = "uk_billing_notice", columnNames = { "user_id", "kind", "period_end" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class BillingNotice {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private NoticeKind kind;

	@Column(name = "period_end", nullable = false)
	private Instant periodEnd;

	@Column(name = "sent_at", nullable = false)
	private Instant sentAt;
}
