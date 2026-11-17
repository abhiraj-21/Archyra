package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.common.InvalidTokenException;
import com.abhiraj.archyra.mail.EmailService;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * Email verification and password reset. The request-style methods ({@link #resendVerification},
 * {@link #forgotPassword}) do the same visible thing for every address, so they cannot be used to find out who is
 * registered; any email goes out asynchronously.
 */
@Service
@RequiredArgsConstructor
class AccountEmailFlows {

	private final UserService userService;
	private final OneTimeTokenService tokens;
	private final RefreshTokenService refreshTokens;
	private final EmailService emailService;
	private final PasswordEncoder passwordEncoder;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;
	private final Clock clock;

	/** Issues a fresh verification token (voiding earlier ones) and emails the link. */
	void sendVerificationEmail(User user) {
		String token = tokens.issue(user.getId(), OneTimePurpose.EMAIL_VERIFICATION, properties.verificationTokenTtl());
		emailService.sendVerificationEmail(user.getEmail(), user.getName(), link("/verify-email", token));
	}

	/** Sends a new verification email if, and only if, the account exists, is enabled and is still unverified. */
	void resendVerification(String email) {
		userService.findByEmail(email)
				.filter(user -> user.isEnabled() && !user.isEmailVerified())
				.ifPresent(this::sendVerificationEmail);
	}

	/** Marks the email verified. The token is consumed in the same transaction. */
	@Transactional
	void verifyEmail(String rawToken) {
		var userId = tokens.consume(rawToken, OneTimePurpose.EMAIL_VERIFICATION).orElseThrow(InvalidTokenException::new);
		if (userService.findById(userId).isEmpty()) {
			throw new InvalidTokenException(); // rolls the token back with it
		}
		userService.markEmailVerified(userId);
		audit.event("EMAIL_VERIFIED", userId, null);
	}

	/**
	 * Emails a reset link to an enabled account. Accounts that only use a social login may use this too, to add a
	 * password: they have shown they control the inbox.
	 */
	void forgotPassword(String email) {
		userService.findByEmail(email).filter(User::isEnabled).ifPresent(user -> {
			String token = tokens.issue(user.getId(), OneTimePurpose.PASSWORD_RESET, properties.passwordResetTokenTtl());
			emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), link("/reset-password", token));
		});
	}

	/**
	 * Sets the new password and ends every session of the user. One transaction: if the new password is not
	 * acceptable the token stays usable. There is no automatic login afterwards.
	 */
	@Transactional
	void resetPassword(String rawToken, String newPassword, ClientInfo client) {
		var userId = tokens.consume(rawToken, OneTimePurpose.PASSWORD_RESET).orElseThrow(InvalidTokenException::new);
		Optional<User> user = userService.findById(userId).filter(User::isEnabled);
		if (user.isEmpty()) {
			throw new InvalidTokenException();
		}
		PasswordPolicy.check(newPassword, user.get().getEmail());
		userService.changePassword(userId, passwordEncoder.encode(newPassword), clock.instant());
		refreshTokens.revokeAllForUser(userId);
		audit.event("PASSWORD_RESET", userId, client.ip());
	}

	private String link(String path, String token) {
		String base = properties.frontendBaseUrl();
		return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path + "?token=" + token;
	}
}
