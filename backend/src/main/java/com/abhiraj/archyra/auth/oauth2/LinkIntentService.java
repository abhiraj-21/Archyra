package com.abhiraj.archyra.auth.oauth2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.auth.TokenHasher;
import com.abhiraj.archyra.user.Provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Link intents: the proof that the signed-in user, in this browser, asked to link this provider.
 *
 * <p>{@link #create} returns two random 256-bit secrets: the {@code token} (goes in the authorization URL) and the
 * {@code bindingSecret} (goes in an HttpOnly cookie). {@link #consume} needs both, so a crafted URL sent to someone
 * else is useless without the cookie of the browser that asked. Used or expired rows are purged lazily and daily.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkIntentService {

	/** How long an intent, and the handshake it starts, stays valid. */
	public static final Duration TTL = Duration.ofMinutes(5);

	private static final Duration PURGE_AFTER = Duration.ofDays(1);

	/** @param token the value of {@code link_intent}; @param bindingSecret the value of the binding cookie */
	public record Issued(String token, String bindingSecret, Duration ttl) {
	}

	/** What a valid intent authorizes: link {@code provider} to {@code userId} until {@code expiresAt}. */
	record Validated(UUID userId, Provider provider, Instant expiresAt) {
	}

	private final LinkIntentRepository repository;
	private final Clock clock;

	@Transactional
	public Issued create(UUID userId, Provider provider) {
		Instant now = clock.instant();
		repository.purge(now.minus(PURGE_AFTER));
		String token = TokenHasher.newToken();
		String bindingSecret = TokenHasher.newToken();
		repository.save(new LinkIntent(TokenHasher.hash(token), TokenHasher.hash(bindingSecret), userId, provider, now.plus(TTL)));
		return new Issued(token, bindingSecret, TTL);
	}

	/**
	 * Uses the intent up when, and only when, it exists, is unexpired and unused, was issued for this provider, and
	 * the binding secret matches. Otherwise nothing changes and the reason is not revealed.
	 */
	@Transactional
	Optional<Validated> consume(String token, String bindingSecret, Provider provider) {
		if (token == null || bindingSecret == null || token.isBlank() || bindingSecret.isBlank()) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		return repository.findByTokenHash(TokenHasher.hash(token))
				.filter(intent -> intent.getProvider() == provider)
				.filter(intent -> intent.getConsumedAt() == null && intent.getExpiresAt().isAfter(now))
				.filter(intent -> constantTimeEquals(intent.getBindingHash(), TokenHasher.hash(bindingSecret)))
				.filter(intent -> repository.consume(intent.getId(), now) == 1)
				.map(intent -> new Validated(intent.getUserId(), intent.getProvider(), intent.getExpiresAt()));
	}

	/** An account is being deleted: its pending link intents go with it. */
	@Transactional
	public void deleteAllOf(UUID userId) {
		repository.deleteAllOf(userId);
	}

	@Scheduled(cron = "${app.security.link-intent-purge-cron:0 41 3 * * *}")
	@Transactional
	public void purge() {
		int deleted = repository.purge(clock.instant().minus(PURGE_AFTER));
		if (deleted > 0) {
			log.info("Purged {} link intents", deleted);
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
