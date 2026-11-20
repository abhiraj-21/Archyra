package com.abhiraj.archyra.usage;

import java.time.Instant;
import java.util.UUID;

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
 * A repository link a deleted account had paid an analysis for ({@code usage_ledger_repository}): one row per identity
 * key and per analysis, so a repository paid for twice appears twice. It holds the canonical repository URL and the time
 * the analysis was created (to restore the most recent ones first) and nothing else: no graph, code, chunks, answers,
 * conversations, email, user id or analysis id. Never logged next to a user id or a key. Written only by
 * {@link UsageLedgerService#record}, which replaces a key's whole set.
 */
@Entity
@Table(name = "usage_ledger_repository", indexes = @Index(name = "idx_usage_ledger_repository_key", columnList = "identity_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RetainedRepository {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "identity_key", nullable = false, updatable = false, length = 64)
	private String identityKey;

	@Column(name = "repository_url", nullable = false, updatable = false, length = 200)
	private String repositoryUrl;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	RetainedRepository(String identityKey, String repositoryUrl, Instant createdAt) {
		this.identityKey = identityKey;
		this.repositoryUrl = repositoryUrl;
		this.createdAt = createdAt;
	}
}
