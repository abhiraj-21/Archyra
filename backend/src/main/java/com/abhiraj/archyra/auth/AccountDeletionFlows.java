package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.common.AccountDeletionRuleException;
import com.abhiraj.archyra.common.AccountDisabledException;
import com.abhiraj.archyra.common.InvalidCredentialsException;
import com.abhiraj.archyra.common.InvalidTokenException;
import com.abhiraj.archyra.common.ServiceBusyException;
import com.abhiraj.archyra.mail.EmailService;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.AccountDeletionService;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Authorising the deletion of an account (Settings → Danger zone); the deletion itself is
 * {@link AccountDeletionService}. A bearer token alone is never enough:
 * <ul>
 * <li>an account <b>with a password</b> sends it ({@link #deleteWithPassword}); a wrong one counts towards the same
 *     lockout as a failed login, like Change password;</li>
 * <li>an account <b>without</b> one (Google or GitHub only) asks for a confirmation link ({@link #requestLink}), mailed
 *     to its verified address, and confirms with the one-time token ({@link #confirm}): single use, valid 15 minutes,
 *     and only for the user who asked.</li>
 * </ul>
 * The wrong flow for the account type is {@code 400 DELETE_METHOD_MISMATCH}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AccountDeletionFlows {

	/** How long the emailed confirmation link works. */
	static final Duration CONFIRMATION_TTL = Duration.ofMinutes(15);

	private final UserService userService;
	private final AccountDeletionService deletion;
	private final OneTimeTokenService tokens;
	private final TransactionTemplate transactions;
	private final PasswordEncoder passwordEncoder;
	private final EmailService emailService;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;
	private final Clock clock;

	/**
	 * Not transactional on purpose, like the password change: a wrong password is counted in a transaction of its own,
	 * which the failure that follows must not roll back.
	 */
	void deleteWithPassword(UUID userId, String password, ClientInfo client) {
		User user = enabledUser(userId);
		if (!user.hasPassword()) {
			throw AccountDeletionRuleException.useTheEmailedLink();
		}
		Instant now = clock.instant();
		if (user.isLocked(now) || PasswordPolicy.exceedsHashLimit(password) || !passwordEncoder.matches(password, user.getPasswordHash())) {
			if (!user.isLocked(now)) { // while locked, further guesses are neither counted nor checked
				boolean lockedNow = userService.recordFailedLogin(userId, properties.lockout().maxFailedAttempts(),
						properties.lockout().lockDuration(), now);
				audit.warn(lockedNow ? "ACCOUNT_LOCKED" : "ACCOUNT_DELETE_REFUSED", userId, client.ip());
			}
			throw new InvalidCredentialsException();
		}
		delete(userId, client);
	}

	/** Emails the confirmation link to the account's verified address, voiding an earlier unused one. */
	void requestLink(UUID userId) {
		User user = enabledUser(userId);
		if (user.hasPassword()) {
			throw AccountDeletionRuleException.useThePassword();
		}
		String token = tokens.issue(userId, OneTimePurpose.ACCOUNT_DELETE, CONFIRMATION_TTL);
		emailService.sendAccountDeletionRequest(user.getEmail(), user.getName(), link("/settings/delete-confirm", token));
		audit.event("ACCOUNT_DELETE_REQUESTED", userId, null);
	}

	/**
	 * Uses the token up and deletes the account in one transaction: if anything refuses (another user's token, the last
	 * administrator, a running analysis) the token stays usable. A token that belongs to someone else is
	 * indistinguishable from an expired or used one.
	 */
	void confirm(UUID userId, String rawToken, ClientInfo client) {
		AccountDeletionService.Deleted deleted = inTransaction(() -> {
			UUID owner = tokens.consume(rawToken, OneTimePurpose.ACCOUNT_DELETE).orElseThrow(InvalidTokenException::new);
			if (!owner.equals(userId)) {
				throw new InvalidTokenException(); // rolls the consumption back: the owner's link still works
			}
			User user = enabledUser(userId);
			if (user.hasPassword()) {
				throw AccountDeletionRuleException.useThePassword();
			}
			return deletion.delete(userId, client.ip());
		});
		deletion.afterDeletion(deleted);
	}

	private void delete(UUID userId, ClientInfo client) {
		deletion.afterDeletion(inTransaction(() -> deletion.delete(userId, client.ip())));
	}

	/** One transaction around {@code work}; the steps after the commit are the caller's, outside it. */
	private AccountDeletionService.Deleted inTransaction(java.util.function.Supplier<AccountDeletionService.Deleted> work) {
		try {
			return transactions.execute(status -> work.get());
		}
		catch (PessimisticLockingFailureException ex) {
			log.warn("Gave up waiting for the usage lock of a user: {}", ex.getClass().getSimpleName());
			throw new ServiceBusyException("Too many requests are being processed for your account. Please try again.");
		}
	}

	/** A missing account is a 401 (a repeated call after the deletion), a disabled one a 403. */
	private User enabledUser(UUID userId) {
		User user = userService.findById(userId)
				.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("The account no longer exists"));
		if (!user.isEnabled()) {
			throw new AccountDisabledException();
		}
		return user;
	}

	private String link(String path, String token) {
		String base = properties.frontendBaseUrl();
		return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path + "?token=" + token;
	}
}
