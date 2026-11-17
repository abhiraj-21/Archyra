package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the token tables small. A token is deleted once it has been expired, revoked or used for more than
 * {@value #RETENTION_DAYS} days. Revoked refresh tokens are kept that long on purpose: presenting one is how a
 * stolen token is recognised, and a refresh token never lives longer than that anyway.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TokenPurgeJob {

	static final int RETENTION_DAYS = 7;

	private final RefreshTokenRepository refreshTokens;
	private final OneTimeTokenRepository oneTimeTokens;
	private final Clock clock;

	/** Once a day, at a quiet minute. Override with {@code app.security.token-purge-cron}. */
	@Scheduled(cron = "${app.security.token-purge-cron:0 17 3 * * *}")
	@Transactional
	public void purge() {
		Instant cutoff = clock.instant().minus(Duration.ofDays(RETENTION_DAYS));
		long refresh = refreshTokens.deleteByExpiresAtBefore(cutoff) + refreshTokens.deleteByRevokedAtBefore(cutoff);
		long oneTime = oneTimeTokens.deleteByExpiresAtBefore(cutoff) + oneTimeTokens.deleteByUsedAtBefore(cutoff);
		if (refresh + oneTime > 0) {
			log.info("Purged {} refresh tokens and {} one-time tokens", refresh, oneTime);
		}
	}
}
