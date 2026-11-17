package com.abhiraj.archyra.user;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A social login linked to a user ({@code linked_identities}). An identity is matched by
 * {@code (provider, providerUserId)}, never by email alone after the first link.
 */
@Entity
@Table(name = "linked_identities",
		uniqueConstraints = @UniqueConstraint(name = "uk_linked_identity_provider_user",
				columnNames = { "provider", "provider_user_id" }),
		indexes = @Index(name = "idx_linked_identity_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkedIdentity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private Provider provider;

	/** Google {@code sub}, or the numeric GitHub id as a string. */
	@Column(name = "provider_user_id", nullable = false, length = 100, updatable = false)
	private String providerUserId;

	/** The email the provider reported when the identity was linked. */
	@Column(name = "provider_email", nullable = false, length = 254)
	private String providerEmail;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public LinkedIdentity(UUID userId, Provider provider, String providerUserId, String providerEmail) {
		this.userId = userId;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.providerEmail = providerEmail;
	}
}
