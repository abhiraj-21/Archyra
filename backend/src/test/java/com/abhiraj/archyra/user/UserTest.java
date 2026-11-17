package com.abhiraj.archyra.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
	private static final Duration LOCK = Duration.ofMinutes(15);

	private final User user = User.registered("a@example.com", "Ada", "{bcrypt}hash", NOW);

	@Test
	void aRegisteredUserIsAnUnverifiedFreeUser() {
		assertThat(user.getRole()).isEqualTo(Role.USER);
		assertThat(user.getPlan()).isEqualTo(Plan.FREE);
		assertThat(user.isEmailVerified()).isFalse();
		assertThat(user.isEnabled()).isTrue();
		assertThat(user.hasPassword()).isTrue();
	}

	@Test
	void aSocialUserIsVerifiedAndHasNoPassword() {
		User social = User.fromSocialLogin("b@example.com", "Bo");

		assertThat(social.isEmailVerified()).isTrue();
		assertThat(social.hasPassword()).isFalse();
		assertThat(social.getRole()).isEqualTo(Role.USER);
		assertThat(social.getPlan()).isEqualTo(Plan.FREE);
	}

	@Test
	void anAdministratorIsVerifiedAndEnabled() {
		User admin = User.administrator("root@example.com", "Root", "{bcrypt}hash", NOW);

		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.isEmailVerified()).isTrue();
		assertThat(admin.isEnabled()).isTrue();
	}

	@Test
	void theAccountLocksAtTheThresholdAndUnlocksAfterTheDuration() {
		for (int i = 0; i < 4; i++) {
			user.recordFailedLogin(5, LOCK, NOW);
		}
		assertThat(user.isLocked(NOW)).isFalse();

		user.recordFailedLogin(5, LOCK, NOW);

		assertThat(user.isLocked(NOW)).isTrue();
		assertThat(user.isLocked(NOW.plus(LOCK).minusSeconds(1))).isTrue();
		assertThat(user.isLocked(NOW.plus(LOCK))).isFalse();
	}

	@Test
	void anExpiredLockStartsAFreshCount() {
		for (int i = 0; i < 5; i++) {
			user.recordFailedLogin(5, LOCK, NOW);
		}
		Instant later = NOW.plus(LOCK).plusSeconds(1);

		user.recordFailedLogin(5, LOCK, later);

		assertThat(user.isLocked(later)).isFalse();
		assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
	}

	@Test
	void aSuccessfulLoginClearsTheCounterAndRecordsTheTime() {
		user.recordFailedLogin(5, LOCK, NOW);

		user.recordSuccessfulLogin(NOW.plusSeconds(5));

		assertThat(user.getFailedLoginAttempts()).isZero();
		assertThat(user.getLastLoginAt()).isEqualTo(NOW.plusSeconds(5));
	}

	@Test
	void clearingThePasswordRecordsWhen() {
		user.clearPassword(NOW.plusSeconds(60));

		assertThat(user.hasPassword()).isFalse();
		assertThat(user.getPasswordChangedAt()).isEqualTo(NOW.plusSeconds(60));
	}

	@Test
	void emailsAreNormalised() {
		assertThat(UserService.normalizeEmail("  Ada@Example.COM ")).isEqualTo("ada@example.com");
	}

	@Test
	void providersMapFromRegistrationIds() {
		assertThat(Provider.fromRegistrationId("google")).isEqualTo(Provider.GOOGLE);
		assertThat(Provider.fromRegistrationId("github")).isEqualTo(Provider.GITHUB);
	}
}
