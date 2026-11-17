package com.abhiraj.archyra.auth;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A single-use token sent by email ({@code one_time_tokens}): email verification or password reset. Only the
 * SHA-256 hash of the token is stored.
 */
@Entity
@Table(name = "one_time_tokens", indexes = {
		@Index(name = "idx_one_time_token_user", columnList = "user_id"),
		@Index(name = "uk_one_time_token_hash", columnList = "token_hash", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class OneTimeToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private OneTimePurpose purpose;

	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	OneTimeToken(UUID userId, OneTimePurpose purpose, String tokenHash, Instant expiresAt) {
		this.userId = userId;
		this.purpose = purpose;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	boolean isUsable(Instant now) {
		return usedAt == null && expiresAt.isAfter(now);
	}

	void markUsed(Instant now) {
		this.usedAt = now;
	}
}
