package com.abhiraj.archyra.auth;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One issued refresh token ({@code refresh_tokens}). Only the SHA-256 hash of the opaque token is stored.
 * Tokens of one login share a {@code familyId}; each refresh revokes the presented token and replaces it with
 * a new one of the same family, so presenting a revoked token means it was stolen and kills the whole family.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
		@Index(name = "idx_refresh_token_user", columnList = "user_id"),
		@Index(name = "idx_refresh_token_family", columnList = "family_id"),
		@Index(name = "uk_refresh_token_hash", columnList = "token_hash", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "replaced_by_id")
	private UUID replacedById;

	@Column(name = "user_agent", length = 255)
	private String userAgent;

	@Column(length = 45)
	private String ip;

	RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant expiresAt, String userAgent, String ip) {
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.familyId = familyId;
		this.expiresAt = expiresAt;
		this.userAgent = truncate(userAgent, 255);
		this.ip = truncate(ip, 45);
	}

	boolean isRevoked() {
		return revokedAt != null;
	}

	boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}

	void revoke(Instant now) {
		if (revokedAt == null) {
			revokedAt = now;
		}
	}

	void replaceWith(RefreshToken next, Instant now) {
		revoke(now);
		this.replacedById = next.getId();
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}
}
