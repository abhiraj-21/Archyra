package com.abhiraj.archyra.usage;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Check;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What a deleted account had consumed ({@code usage_ledger}): one row per identity key and usage type, holding only a
 * keyed hash and a count. No email, name, repository URL or content, and nothing that can be reversed without the
 * secret. Written only by {@link UsageLedgerRepository#raiseTo}; counts only go up.
 */
@Entity
@Table(name = "usage_ledger",
		uniqueConstraints = @UniqueConstraint(name = "uk_usage_ledger_key_type", columnNames = { "identity_key", "usage_type" }))
@Check(name = "ck_usage_ledger_non_negative", constraints = "used_count >= 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageLedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "identity_key", nullable = false, updatable = false, length = 64)
	private String identityKey;

	@Column(name = "usage_type", nullable = false, updatable = false, length = 30)
	private String usageType;

	@Column(name = "used_count", nullable = false)
	private int usedCount;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
