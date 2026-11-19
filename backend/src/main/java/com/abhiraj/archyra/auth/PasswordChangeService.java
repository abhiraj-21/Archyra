package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.common.AccountDisabledException;
import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.common.PasswordRuleException;
import com.abhiraj.archyra.common.UserNotFoundException;
import com.abhiraj.archyra.mail.EmailService;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Changing the password of a signed-in user (Settings → Password). The current password must be proven even though a
 * valid access token is presented: a stolen token must not be enough to take over the account. A wrong current
 * password counts like a failed login (same lockout), so the form cannot be used to guess the password.
 *
 * <p>On success every refresh-token family of the user ends and a fresh one starts for the caller, so the browser
 * that changed the password stays signed in and every other device has to sign in again (their access tokens, which
 * are stateless, expire within minutes). An account without a password cannot use this: setting a first password
 * needs proof of the inbox, which the forgot-password flow gives.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PasswordChangeService {

	private final UserService userService;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokens;
	private final JwtService jwtService;
	private final EmailService emailService;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;
	private final TransactionTemplate transactions;
	private final Clock clock;

	/**
	 * @throws PasswordRuleException   {@code INCORRECT_PASSWORD} (also while the account is locked) or {@code NO_PASSWORD_SET}
	 * @throws InvalidRequestException if the new password is not acceptable or equals the current one
	 */
	AuthService.Session change(UUID userId, String currentPassword, String newPassword, ClientInfo client) {
		User user = userService.findById(userId).orElseThrow(UserNotFoundException::new);
		if (!user.isEnabled()) {
			throw new AccountDisabledException();
		}
		if (!user.hasPassword()) {
			throw PasswordRuleException.noPasswordSet();
		}
		Instant now = clock.instant();
		if (user.isLocked(now) || PasswordPolicy.exceedsHashLimit(currentPassword)
				|| !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			if (!user.isLocked(now)) { // while locked, further guesses are refused without being counted or checked
				boolean lockedNow = userService.recordFailedLogin(userId, properties.lockout().maxFailedAttempts(),
						properties.lockout().lockDuration(), now);
				audit.warn(lockedNow ? "ACCOUNT_LOCKED" : "PASSWORD_CHANGE_REFUSED", userId, client.ip());
			}
			throw PasswordRuleException.incorrectPassword();
		}
		PasswordPolicy.check(newPassword, user.getEmail());
		if (newPassword.equals(currentPassword)) {
			throw new InvalidRequestException("The new password must be different from your current one");
		}

		String hash = passwordEncoder.encode(newPassword);
		// One transaction: the new hash, the end of the old sessions and the caller's new session stand or fall together.
		AuthService.Session session = transactions.execute(status -> {
			userService.changePassword(userId, hash, now);
			refreshTokens.revokeAllForUser(userId);
			return new AuthService.Session(jwtService.issueFor(user), refreshTokens.startFamily(userId, client));
		});
		audit.event("PASSWORD_CHANGED", userId, client.ip());
		notifyOwner(user, now);
		return session;
	}

	/** After commit, and never failing the change: the password is already different. */
	private void notifyOwner(User user, Instant at) {
		try {
			emailService.sendPasswordChangedNotice(user.getEmail(), user.getName(), at, forgotPasswordLink());
		}
		catch (RuntimeException ex) {
			log.warn("Could not send a password-changed notice ({})", ex.getClass().getSimpleName());
		}
	}

	private String forgotPasswordLink() {
		String base = properties.frontendBaseUrl();
		return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/forgot-password";
	}
}
