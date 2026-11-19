package com.abhiraj.archyra.user;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.abhiraj.archyra.common.AccountDisabledException;
import com.abhiraj.archyra.common.LinkedIdentityRuleException;
import com.abhiraj.archyra.common.UserNotFoundException;
import com.abhiraj.archyra.mail.EmailService;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.usage.UsageLedgerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Linking and unlinking social identities for a signed-in user (Settings → Sign-in methods). This is separate from
 * the login resolver, which matches identities at sign-in; nothing here ever looks an account up by email.
 *
 * <p>Both operations lock the user's row first, so two requests for one account are serialized: two concurrent
 * unlinks cannot both pass the "is this the last way to sign in" check. The notification email goes out after the
 * transaction commits, and a mail failure is logged and never fails the operation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityLinkService {

	/** What a provider told us about the signed-in person. {@code email} and {@code login} may be null. */
	public record ProviderIdentityDetails(Provider provider, String providerUserId, String email, String login) {
	}

	public record IdentityView(Provider provider, String email, String login, Instant linkedAt) {
	}

	public record Overview(boolean hasPassword, List<IdentityView> identities) {
	}

	public enum LinkResult {
		LINKED, ALREADY_LINKED, IDENTITY_OWNED_BY_ANOTHER_ACCOUNT, PROVIDER_ALREADY_LINKED, ACCOUNT_DISABLED, ACCOUNT_MISSING
	}

	private final UserRepository users;
	private final LinkedIdentityRepository identities;
	private final UsageLedgerService usageLedger;
	private final EmailService emailService;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;
	private final Clock clock;

	/** The password flag and the linked identities of an enabled account. */
	@Transactional(readOnly = true)
	public Overview overview(UUID userId) {
		User user = requireEnabled(users.findById(userId).orElseThrow(UserNotFoundException::new));
		List<IdentityView> views = identities.findByUserId(userId).stream()
				.sorted(Comparator.comparing(LinkedIdentity::getProvider))
				.map(identity -> new IdentityView(identity.getProvider(), identity.getProviderEmail(),
						identity.getProviderLogin(), identity.getCreatedAt()))
				.toList();
		return new Overview(user.hasPassword(), views);
	}

	/** Whether the account already has an identity of that provider. */
	@Transactional(readOnly = true)
	public boolean isLinked(UUID userId, Provider provider) {
		return identities.findByUserIdAndProvider(userId, provider).isPresent();
	}

	/** Fails with 401-style errors for a missing or disabled account; used before a link is started. */
	@Transactional(readOnly = true)
	public void requireEnabledAccount(UUID userId) {
		requireEnabled(users.findById(userId).orElseThrow(UserNotFoundException::new));
	}

	/**
	 * Attaches the identity to the user. The user comes from the caller (the handshake session), never from the
	 * provider's email, and the provider's email is stored for display only.
	 *
	 * @throws org.springframework.dao.DataIntegrityViolationException when another account claimed the same
	 *         provider identity between the check and the insert; the caller reports it as already linked
	 */
	@Transactional
	public LinkResult link(UUID userId, ProviderIdentityDetails details) {
		Optional<User> found = users.findByIdForUpdate(userId);
		if (found.isEmpty()) {
			return LinkResult.ACCOUNT_MISSING;
		}
		User user = found.get();
		if (!user.isEnabled()) {
			return LinkResult.ACCOUNT_DISABLED;
		}
		Optional<LinkedIdentity> owner = identities.findByProviderAndProviderUserId(details.provider(), details.providerUserId());
		if (owner.isPresent()) {
			return owner.get().getUserId().equals(userId) ? LinkResult.ALREADY_LINKED : LinkResult.IDENTITY_OWNED_BY_ANOTHER_ACCOUNT;
		}
		if (identities.findByUserIdAndProvider(userId, details.provider()).isPresent()) {
			return LinkResult.PROVIDER_ALREADY_LINKED;
		}
		identities.saveAndFlush(new LinkedIdentity(userId, details.provider(), details.providerUserId(),
				clean(details.email()), clean(details.login())));
		// Merge, never lower: a person who deleted an account must not shed their usage by linking the old identity to a new one.
		usageLedger.restoreInto(userId, Set.of(usageLedger.identityKey(details.provider(), details.providerUserId())));
		audit.event("IDENTITY_LINKED_" + details.provider(), userId, null);
		Instant now = clock.instant();
		afterCommit(() -> emailService.sendIdentityLinkedNotice(user.getEmail(), user.getName(),
				displayName(details.provider()), now, forgotPasswordLink()));
		return LinkResult.LINKED;
	}

	/**
	 * Removes the identity unless it is the account's last way to sign in (no password and no other identity).
	 *
	 * @throws LinkedIdentityRuleException {@code IDENTITY_NOT_FOUND} or {@code LAST_LOGIN_METHOD}
	 */
	@Transactional
	public void unlink(UUID userId, Provider provider) {
		User user = requireEnabled(users.findByIdForUpdate(userId).orElseThrow(UserNotFoundException::new));
		LinkedIdentity identity = identities.findByUserIdAndProvider(userId, provider)
				.orElseThrow(LinkedIdentityRuleException::identityNotFound);
		long remaining = identities.countByUserId(userId) - 1;
		if (!user.hasPassword() && remaining == 0) {
			throw LinkedIdentityRuleException.lastLoginMethod();
		}
		identities.delete(identity);
		identities.flush();
		audit.event("IDENTITY_UNLINKED_" + provider, userId, null);
		Instant now = clock.instant();
		afterCommit(() -> emailService.sendIdentityUnlinkedNotice(user.getEmail(), user.getName(),
				displayName(provider), now, forgotPasswordLink()));
	}

	public static String displayName(Provider provider) {
		return switch (provider) {
			case GOOGLE -> "Google";
			case GITHUB -> "GitHub";
		};
	}

	private static User requireEnabled(User user) {
		if (!user.isEnabled()) {
			throw new AccountDisabledException();
		}
		return user;
	}

	private static String clean(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.replaceAll("\\p{Cntrl}", " ").trim();
		return trimmed.isEmpty() ? null : trimmed.length() <= 254 ? trimmed : trimmed.substring(0, 254);
	}

	private String forgotPasswordLink() {
		String base = properties.frontendBaseUrl();
		return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/forgot-password";
	}

	/** Runs the action once the surrounding transaction has committed, and never lets it throw into the caller. */
	private void afterCommit(Runnable action) {
		Runnable safe = () -> {
			try {
				action.run();
			}
			catch (RuntimeException ex) {
				log.warn("Could not send an account-link notice ({})", ex.getClass().getSimpleName());
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					safe.run();
				}
			});
		}
		else {
			safe.run();
		}
	}
}
