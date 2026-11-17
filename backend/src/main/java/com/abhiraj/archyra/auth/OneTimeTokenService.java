package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Single-use tokens for email verification and password reset. Only hashes are stored. */
@Service
@RequiredArgsConstructor
class OneTimeTokenService {

	private final OneTimeTokenRepository repository;
	private final Clock clock;

	/**
	 * Creates a token for the user and invalidates any earlier unused one of the same kind, so only the latest
	 * email works.
	 *
	 * @return the raw token, to be put in the emailed link; it is not recoverable afterwards
	 */
	@Transactional
	String issue(UUID userId, OneTimePurpose purpose, Duration ttl) {
		Instant now = clock.instant();
		repository.findByUserIdAndPurposeAndUsedAtIsNull(userId, purpose).forEach(token -> token.markUsed(now));
		String raw = TokenHasher.newToken();
		repository.save(new OneTimeToken(userId, purpose, TokenHasher.hash(raw), now.plus(ttl)));
		return raw;
	}

	/**
	 * Uses the token up. Empty if it is unknown, of another kind, expired or already used. Call inside the
	 * transaction that applies its effect, so a failure there leaves the token usable.
	 */
	@Transactional
	Optional<UUID> consume(String rawToken, OneTimePurpose purpose) {
		Instant now = clock.instant();
		return repository.findForUpdate(TokenHasher.hash(rawToken), purpose)
				.filter(token -> token.isUsable(now))
				.map(token -> {
					token.markUsed(now);
					return token.getUserId();
				});
	}

	/** Invalidates every unused token of that kind, e.g. after the password changed by another route. */
	@Transactional
	void invalidateAll(UUID userId, OneTimePurpose purpose) {
		Instant now = clock.instant();
		repository.findByUserIdAndPurposeAndUsedAtIsNull(userId, purpose).forEach(token -> token.markUsed(now));
	}
}
