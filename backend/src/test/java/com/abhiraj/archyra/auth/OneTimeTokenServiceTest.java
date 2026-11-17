package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OneTimeTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-06-01T08:00:00Z");

	@Mock
	private OneTimeTokenRepository repository;
	private OneTimeTokenService service;
	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new OneTimeTokenService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void issuingStoresOnlyTheHashWithTheRequestedExpiry() {
		String raw = service.issue(userId, OneTimePurpose.PASSWORD_RESET, Duration.ofMinutes(30));

		var saved = ArgumentCaptor.forClass(OneTimeToken.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getTokenHash()).isEqualTo(TokenHasher.hash(raw)).isNotEqualTo(raw);
		assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
		assertThat(saved.getValue().getPurpose()).isEqualTo(OneTimePurpose.PASSWORD_RESET);
	}

	@Test
	void issuingVoidsEarlierUnusedTokensOfTheSameKind() {
		OneTimeToken earlier = new OneTimeToken(userId, OneTimePurpose.EMAIL_VERIFICATION, "h", NOW.plusSeconds(600));
		given(repository.findByUserIdAndPurposeAndUsedAtIsNull(userId, OneTimePurpose.EMAIL_VERIFICATION))
				.willReturn(List.of(earlier));

		service.issue(userId, OneTimePurpose.EMAIL_VERIFICATION, Duration.ofHours(24));

		assertThat(earlier.getUsedAt()).isEqualTo(NOW);
	}

	@Test
	void aValidTokenIsConsumedOnceAndReturnsItsUser() {
		OneTimeToken token = new OneTimeToken(userId, OneTimePurpose.PASSWORD_RESET, TokenHasher.hash("raw"), NOW.plusSeconds(60));
		given(repository.findForUpdate(TokenHasher.hash("raw"), OneTimePurpose.PASSWORD_RESET)).willReturn(Optional.of(token));

		assertThat(service.consume("raw", OneTimePurpose.PASSWORD_RESET)).contains(userId);
		assertThat(token.getUsedAt()).isEqualTo(NOW);
		assertThat(service.consume("raw", OneTimePurpose.PASSWORD_RESET)).isEmpty(); // second use
	}

	@Test
	void anExpiredTokenIsRefusedAndStaysUnused() {
		OneTimeToken token = new OneTimeToken(userId, OneTimePurpose.PASSWORD_RESET, TokenHasher.hash("raw"), NOW);
		given(repository.findForUpdate(TokenHasher.hash("raw"), OneTimePurpose.PASSWORD_RESET)).willReturn(Optional.of(token));

		assertThat(service.consume("raw", OneTimePurpose.PASSWORD_RESET)).isEmpty();
		assertThat(token.getUsedAt()).isNull();
	}

	@Test
	void aTokenOfAnotherKindOrUnknownIsRefused() {
		given(repository.findForUpdate(any(), any())).willReturn(Optional.empty());

		assertThat(service.consume("raw", OneTimePurpose.EMAIL_VERIFICATION)).isEmpty();
	}
}
