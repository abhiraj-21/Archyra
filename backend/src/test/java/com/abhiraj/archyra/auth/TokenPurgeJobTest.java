package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.abhiraj.archyra.TestcontainersConfiguration;

/** The purge against the real tables: what is old enough goes, everything recent or still useful stays. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TokenPurgeJobTest {

	private static final Instant NOW = Instant.parse("2026-09-10T03:17:00Z");

	@Autowired
	RefreshTokenRepository refreshTokens;
	@Autowired
	OneTimeTokenRepository oneTimeTokens;

	/** Built by hand so it runs inside this test's transaction (and rolls back with it). */
	private TokenPurgeJob job() {
		return new TokenPurgeJob(refreshTokens, oneTimeTokens, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private RefreshToken refresh(Instant expiresAt, Instant revokedAt) {
		RefreshToken token = new RefreshToken(UUID.randomUUID(), TokenHasher.hash(UUID.randomUUID().toString()), UUID.randomUUID(),
				expiresAt, null, null);
		if (revokedAt != null) {
			token.revoke(revokedAt);
		}
		return refreshTokens.saveAndFlush(token);
	}

	private OneTimeToken oneTime(Instant expiresAt, Instant usedAt) {
		OneTimeToken token = new OneTimeToken(UUID.randomUUID(), OneTimePurpose.PASSWORD_RESET, TokenHasher.hash(UUID.randomUUID().toString()), expiresAt);
		if (usedAt != null) {
			token.markUsed(usedAt);
		}
		return oneTimeTokens.saveAndFlush(token);
	}

	@Test
	void refreshTokensExpiredOrRevokedMoreThanSevenDaysAgoAreDeleted() {
		RefreshToken longExpired = refresh(NOW.minus(Duration.ofDays(8)), null);
		RefreshToken longRevoked = refresh(NOW.plus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(8)));
		RefreshToken recentlyExpired = refresh(NOW.minus(Duration.ofDays(1)), null);
		RefreshToken recentlyRevoked = refresh(NOW.plus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(1)));
		RefreshToken active = refresh(NOW.plus(Duration.ofDays(7)), null);

		job().purge();

		assertThat(refreshTokens.findById(longExpired.getId())).isEmpty();
		assertThat(refreshTokens.findById(longRevoked.getId())).isEmpty();
		assertThat(refreshTokens.findById(recentlyExpired.getId())).isPresent();
		assertThat(refreshTokens.findById(recentlyRevoked.getId())).isPresent(); // kept: reuse detection still needs it
		assertThat(refreshTokens.findById(active.getId())).isPresent();
	}

	@Test
	void oneTimeTokensExpiredOrUsedMoreThanSevenDaysAgoAreDeleted() {
		OneTimeToken longExpired = oneTime(NOW.minus(Duration.ofDays(8)), null);
		OneTimeToken longUsed = oneTime(NOW.plus(Duration.ofHours(1)), NOW.minus(Duration.ofDays(8)));
		OneTimeToken recentlyUsed = oneTime(NOW.plus(Duration.ofHours(1)), NOW.minus(Duration.ofDays(1)));
		OneTimeToken recentlyExpired = oneTime(NOW.minus(Duration.ofDays(2)), null);
		OneTimeToken valid = oneTime(NOW.plus(Duration.ofMinutes(30)), null);

		job().purge();

		assertThat(oneTimeTokens.findById(longExpired.getId())).isEmpty();
		assertThat(oneTimeTokens.findById(longUsed.getId())).isEmpty();
		assertThat(oneTimeTokens.findById(recentlyUsed.getId())).isPresent();
		assertThat(oneTimeTokens.findById(recentlyExpired.getId())).isPresent();
		assertThat(oneTimeTokens.findById(valid.getId())).isPresent();
	}

	@Test
	void anEmptyPurgeIsHarmless() {
		job().purge();
		job().purge();
	}
}
