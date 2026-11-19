package com.abhiraj.archyra.usage;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Free-tier allowances ({@code archyra.usage.*}). They are lifetime allowances, not periodic ones. A missing or
 * negative limit fails startup.
 *
 * @param freeTier     the limit of each {@link UsageType} for users on the FREE plan
 * @param ledgerSecret the key of the usage ledger's HMAC-SHA256 identity keys ({@code USAGE_LEDGER_SECRET}); required, at
 *                     least 32 bytes, so a leaked database cannot be matched against guessed emails
 */
@Validated
@ConfigurationProperties(prefix = "archyra.usage")
public record UsageProperties(@Valid @NotNull FreeTier freeTier, @NotBlank String ledgerSecret) {

	/** The secret of the usage ledger's keyed hashes must be at least this many bytes. */
	public static final int MIN_LEDGER_SECRET_BYTES = 32;

	/** Fewer distinct characters than this is a placeholder, not a secret. */
	private static final int MIN_DISTINCT_CHARACTERS = 10;

	public UsageProperties {
		if (ledgerSecret != null && !ledgerSecret.isBlank()) {
			if (ledgerSecret.getBytes(StandardCharsets.UTF_8).length < MIN_LEDGER_SECRET_BYTES
					|| ledgerSecret.chars().distinct().count() < MIN_DISTINCT_CHARACTERS) {
				throw new IllegalArgumentException("archyra.usage.ledger-secret (USAGE_LEDGER_SECRET) must be at least "
						+ MIN_LEDGER_SECRET_BYTES + " bytes of random text (openssl rand -base64 48)");
			}
		}
	}

	/** Never print the secret. */
	@Override
	public String toString() {
		return "UsageProperties[freeTier=" + freeTier + ", ledgerSecret=<redacted>]";
	}

	/** Boxed so that a missing value is a validation error and not a silent 0. */
	public record FreeTier(
			@NotNull @Min(0) Integer repositoryAnalysisLimit,
			@NotNull @Min(0) Integer aiQueryLimit) {
	}

	/** The switch has no default, so a new {@link UsageType} without a limit does not compile. */
	public int freeLimit(UsageType type) {
		return switch (type) {
			case REPOSITORY_ANALYSIS -> freeTier.repositoryAnalysisLimit();
			case AI_QUERY -> freeTier.aiQueryLimit();
		};
	}
}
