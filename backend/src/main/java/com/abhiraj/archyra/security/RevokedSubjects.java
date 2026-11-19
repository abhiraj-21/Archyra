package com.abhiraj.archyra.security;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * User ids whose access tokens must stop working at once. A deleted account's access token is stateless and would
 * otherwise stay valid for up to its lifetime: long enough to start analyses and ask questions as a user that no
 * longer exists, with fresh counters that the usage ledger never sees. {@code JwtConfig} rejects a token whose
 * {@code sub} is listed here.
 *
 * <p>In memory, like the rate limiter (single instance assumed): entries live longer than any access token, so they
 * need no cleanup. A restart empties it, after which the token works until it expires.
 */
@Component
public class RevokedSubjects {

	/** Longer than the access token lifetime plus the allowed clock skew; the lifetime is 15 minutes by default. */
	private static final Duration KEEP = Duration.ofHours(1);

	private final Cache<String, Boolean> revoked = Caffeine.newBuilder().expireAfterWrite(KEEP).maximumSize(100_000).build();

	public void revoke(UUID userId) {
		revoked.put(userId.toString(), Boolean.TRUE);
	}

	public boolean isRevoked(String subject) {
		return subject != null && revoked.getIfPresent(subject) != null;
	}
}
