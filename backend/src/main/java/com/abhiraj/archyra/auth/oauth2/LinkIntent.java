package com.abhiraj.archyra.auth.oauth2;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.abhiraj.archyra.user.Provider;

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
 * A permission, given to one signed-in user, to start linking one provider ({@code oauth_link_intents}). Only
 * hashes are stored: of the token that travels in the URL and of the binding secret that travels in a cookie.
 * Single use, short lived, and bound to the user, the provider and the browser that asked.
 */
@Entity
@Table(name = "oauth_link_intents", indexes = {
		@Index(name = "uk_link_intent_token_hash", columnList = "token_hash", unique = true),
		@Index(name = "idx_link_intent_user", columnList = "user_id") })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LinkIntent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Column(name = "binding_hash", nullable = false, updatable = false, length = 64)
	private String bindingHash;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private Provider provider;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	LinkIntent(String tokenHash, String bindingHash, UUID userId, Provider provider, Instant expiresAt) {
		this.tokenHash = tokenHash;
		this.bindingHash = bindingHash;
		this.userId = userId;
		this.provider = provider;
		this.expiresAt = expiresAt;
	}
}
