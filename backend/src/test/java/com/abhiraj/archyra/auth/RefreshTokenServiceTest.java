package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
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

import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-05-01T09:00:00Z");
	private static final ClientInfo CLIENT = new ClientInfo("203.0.113.9", "JUnit");

	@Mock
	private RefreshTokenRepository repository;
	@Mock
	private SecurityAuditLogger audit;

	private RefreshTokenService service;
	private final UUID userId = UUID.randomUUID();
	private final UUID familyId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		SecurityProperties properties = new SecurityProperties(
				new SecurityProperties.Jwt(java.util.Base64.getEncoder().encodeToString(new byte[32]), "archyra", Duration.ofMinutes(15)),
				new SecurityProperties.RefreshToken(Duration.ofDays(7), "refresh_token", "/api/v1/auth", true),
				Duration.ofHours(24), Duration.ofMinutes(30), "http://localhost:5173",
				new SecurityProperties.Cors(List.of("http://localhost:5173")),
				new SecurityProperties.OAuth2("/auth/callback", "/login"),
				new SecurityProperties.RateLimit(10, 5, 10, 10, 10, 20, 30, 600), new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
				new SecurityProperties.BootstrapAdmin(null, null));
		service = new RefreshTokenService(repository, properties, audit, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private RefreshToken stored(String raw, Instant expiresAt) {
		return new RefreshToken(userId, TokenHasher.hash(raw), familyId, expiresAt, "JUnit", "203.0.113.9");
	}

	@Test
	void aNewFamilyStoresOnlyTheHashAndExpiresAfterTheConfiguredTtl() {
		given(repository.saveAndFlush(any())).willAnswer(returnsFirstArg());

		RefreshTokenService.Issued issued = service.startFamily(userId, CLIENT);

		var saved = ArgumentCaptor.forClass(RefreshToken.class);
		verify(repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getTokenHash()).isEqualTo(TokenHasher.hash(issued.rawToken())).isNotEqualTo(issued.rawToken());
		assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
		assertThat(saved.getValue().getUserId()).isEqualTo(userId);
	}

	@Test
	void rotatingRevokesTheOldTokenAndIssuesANewOneInTheSameFamily() {
		RefreshToken current = stored("old", NOW.plusSeconds(60));
		given(repository.findByTokenHashForUpdate(TokenHasher.hash("old"))).willReturn(Optional.of(current));
		given(repository.saveAndFlush(any())).willAnswer(returnsFirstArg());

		var rotation = service.rotate("old", CLIENT).orElseThrow();

		assertThat(rotation.userId()).isEqualTo(userId);
		assertThat(rotation.next().rawToken()).isNotEqualTo("old");
		assertThat(current.isRevoked()).isTrue();
		var saved = ArgumentCaptor.forClass(RefreshToken.class);
		verify(repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getFamilyId()).isEqualTo(familyId);
		assertThat(saved.getValue().isRevoked()).isFalse();
	}

	@Test
	void anUnknownTokenIsRefused() {
		given(repository.findByTokenHashForUpdate(any())).willReturn(Optional.empty());

		assertThat(service.rotate("nope", CLIENT)).isEmpty();
	}

	@Test
	void anExpiredTokenIsRefusedAndRevoked() {
		RefreshToken expired = stored("old", NOW.minusSeconds(1));
		given(repository.findByTokenHashForUpdate(TokenHasher.hash("old"))).willReturn(Optional.of(expired));

		assertThat(service.rotate("old", CLIENT)).isEmpty();
		assertThat(expired.isRevoked()).isTrue();
	}

	@Test
	void presentingARevokedTokenRevokesTheWholeFamilyAndIsAudited() {
		RefreshToken stolen = stored("old", NOW.plusSeconds(60));
		stolen.revoke(NOW.minusSeconds(5));
		RefreshToken successor = stored("new", NOW.plusSeconds(60));
		given(repository.findByTokenHashForUpdate(TokenHasher.hash("old"))).willReturn(Optional.of(stolen));
		given(repository.findByFamilyIdAndRevokedAtIsNull(familyId)).willReturn(List.of(successor));

		assertThat(service.rotate("old", CLIENT)).isEmpty();

		assertThat(successor.isRevoked()).isTrue();
		verify(audit).warn("REFRESH_TOKEN_REUSE", userId, "203.0.113.9");
	}

	@Test
	void logoutRevokesTheFamilyOfThePresentedToken() {
		RefreshToken one = stored("a", NOW.plusSeconds(60));
		RefreshToken two = stored("b", NOW.plusSeconds(60));
		given(repository.findByTokenHash(TokenHasher.hash("a"))).willReturn(Optional.of(one));
		given(repository.findByFamilyIdAndRevokedAtIsNull(familyId)).willReturn(List.of(one, two));

		service.revokeFamilyOf("a");

		assertThat(one.isRevoked()).isTrue();
		assertThat(two.isRevoked()).isTrue();
	}

	@Test
	void revokingAllSessionsOfAUserRevokesEveryActiveToken() {
		RefreshToken one = stored("a", NOW.plusSeconds(60));
		RefreshToken two = new RefreshToken(userId, TokenHasher.hash("b"), UUID.randomUUID(), NOW.plusSeconds(60), null, null);
		given(repository.findByUserIdAndRevokedAtIsNull(userId)).willReturn(List.of(one, two));

		service.revokeAllForUser(userId);

		assertThat(one.isRevoked()).isTrue();
		assertThat(two.isRevoked()).isTrue();
	}
}
