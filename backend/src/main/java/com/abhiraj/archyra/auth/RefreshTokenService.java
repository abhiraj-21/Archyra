package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;

import lombok.RequiredArgsConstructor;

/**
 * Opaque refresh tokens with rotation. Each login starts a family; every refresh revokes the presented token and
 * issues a new one in the same family. Presenting a token that was already revoked means it leaked, so the
 * whole family is revoked.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

	/** A freshly issued token. {@code rawToken} exists only here and in the cookie; the database keeps its hash. */
	public record Issued(String rawToken, Instant expiresAt) {

		@Override
		public String toString() {
			return "Issued[rawToken=<redacted>, expiresAt=" + expiresAt + "]";
		}
	}

	/** The outcome of a successful rotation. */
	public record Rotation(UUID userId, Issued next) {
	}

	private final RefreshTokenRepository repository;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;
	private final Clock clock;

	/** Starts a new family for a login. */
	@Transactional
	public Issued startFamily(UUID userId, ClientInfo client) {
		return issue(userId, UUID.randomUUID(), client).issued();
	}

	/**
	 * Exchanges a refresh token for a new one of the same family. Empty if the token is unknown, expired or
	 * revoked; a revoked token also revokes its whole family. Rows are locked so two concurrent requests with
	 * the same token cannot both succeed.
	 */
	@Transactional
	public Optional<Rotation> rotate(String rawToken, ClientInfo client) {
		Instant now = clock.instant();
		RefreshToken current = repository.findByTokenHashForUpdate(TokenHasher.hash(rawToken)).orElse(null);
		if (current == null) {
			return Optional.empty();
		}
		if (current.isRevoked()) {
			revokeFamily(current.getFamilyId(), now);
			audit.warn("REFRESH_TOKEN_REUSE", current.getUserId(), client.ip());
			return Optional.empty();
		}
		if (current.isExpired(now)) {
			current.revoke(now);
			return Optional.empty();
		}
		Created next = issue(current.getUserId(), current.getFamilyId(), client);
		current.replaceWith(next.entity(), now);
		return Optional.of(new Rotation(current.getUserId(), next.issued()));
	}

	/** Logout: revokes the family of the presented token, if it is one of ours. */
	@Transactional
	public void revokeFamilyOf(String rawToken) {
		repository.findByTokenHash(TokenHasher.hash(rawToken))
				.ifPresent(token -> revokeFamily(token.getFamilyId(), clock.instant()));
	}

	/** Password reset or change, or a disabled account: every session of the user ends. */
	@Transactional
	public void revokeAllForUser(UUID userId) {
		Instant now = clock.instant();
		repository.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(now));
	}

	private void revokeFamily(UUID familyId, Instant now) {
		repository.findByFamilyIdAndRevokedAtIsNull(familyId).forEach(token -> token.revoke(now));
	}

	private record Created(RefreshToken entity, Issued issued) {
	}

	private Created issue(UUID userId, UUID familyId, ClientInfo client) {
		String raw = TokenHasher.newToken();
		Instant expiresAt = clock.instant().plus(properties.refreshToken().ttl());
		RefreshToken entity = repository.saveAndFlush(
				new RefreshToken(userId, TokenHasher.hash(raw), familyId, expiresAt, client.userAgent(), client.ip()));
		return new Created(entity, new Issued(raw, expiresAt));
	}
}
