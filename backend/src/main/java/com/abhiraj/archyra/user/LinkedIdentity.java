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
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_linked_identity_provider_user", columnNames = { "provider", "provider_user_id" }),
				// One identity per provider per user: a second, different Google account cannot be linked.
				@UniqueConstraint(name = "uk_linked_identity_user_provider", columnNames = { "user_id", "provider" }) },
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

	/**
	 * The email the provider reported when the identity was linked. Display only: it never changes the account email
	 * and is never used for matching. Null when the provider reported none (linking does not require one).
	 */
	@Column(name = "provider_email", length = 254)
	private String providerEmail;

	/** The GitHub username; null for Google and for identities linked before this column existed. */
	@Column(name = "provider_login", length = 100)
	private String providerLogin;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public LinkedIdentity(UUID userId, Provider provider, String providerUserId, String providerEmail) {
		this(userId, provider, providerUserId, providerEmail, null);
	}

	public LinkedIdentity(UUID userId, Provider provider, String providerUserId, String providerEmail, String providerLogin) {
		this.userId = userId;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.providerEmail = providerEmail;
		this.providerLogin = providerLogin;
	}
}
