package com.abhiraj.archyra.usage;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
 * How much of one {@link UsageType} a user has consumed ({@code user_usage} table): over their lifetime, or within one
 * weekly window for a weekly type. One row per user and type. Rows are created lazily by {@link UserUsageRepository#insertIfAbsent}, never by reading.
 *
 * <p>The unique constraint also serves as the index on {@code user_id}. Like the other features, the user is a plain
 * id with no foreign key.
 */
@Entity
@Table(name = "user_usage",
		uniqueConstraints = @UniqueConstraint(name = "uk_user_usage_user_type", columnNames = { "user_id", "usage_type" }))
@Check(name = "ck_user_usage_non_negative", constraints = "used_count >= 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserUsage {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "usage_type", nullable = false, updatable = false, length = 30)
	private UsageType usageType;

	@Column(name = "used_count", nullable = false, columnDefinition = "integer not null default 0")
	private int usedCount;

	/**
	 * The start of the weekly window {@code used_count} belongs to; only rows of a {@linkplain UsageType#weekly() weekly}
	 * type have one (null for a lifetime type, and for a weekly row from before the allowance became weekly, which counts as
	 * an old window and so as 0).
	 */
	@Column(name = "window_start")
	private Instant windowStart;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Only valid while the row is locked by {@link UserUsageRepository#findForUpdate}. */
	void increment() {
		usedCount++;
	}

	/**
	 * Starts a new window if this row's one is missing or over: the count goes back to 0. Same lock rule as
	 * {@link #increment()}. Never moves a window backwards.
	 */
	void rollOverTo(Instant currentWindowStart) {
		if (isBefore(currentWindowStart)) {
			usedCount = 0;
			windowStart = currentWindowStart;
		}
	}

	/** True when this row's window is missing or ended before {@code currentWindowStart}. */
	boolean isBefore(Instant currentWindowStart) {
		return windowStart == null || windowStart.isBefore(currentWindowStart);
	}
}
