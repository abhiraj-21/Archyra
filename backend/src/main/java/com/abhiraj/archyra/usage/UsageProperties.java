package com.abhiraj.archyra.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Free-tier allowances ({@code archyra.usage.*}). They are lifetime allowances, not periodic ones. A missing or
 * negative limit fails startup.
 *
 * @param freeTier the limit of each {@link UsageType} for users on the FREE plan
 */
@Validated
@ConfigurationProperties(prefix = "archyra.usage")
public record UsageProperties(@Valid @NotNull FreeTier freeTier) {

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
