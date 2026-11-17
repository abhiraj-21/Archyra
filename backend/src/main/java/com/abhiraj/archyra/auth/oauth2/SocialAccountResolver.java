package com.abhiraj.archyra.auth.oauth2;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.auth.RefreshTokenService;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.user.LinkedIdentity;
import com.abhiraj.archyra.user.Provider;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * Decides which Archyra account a social login belongs to:
 * <ol>
 * <li>the provider's email must be verified, otherwise no login;</li>
 * <li>an existing link for {@code (provider, providerUserId)} wins, even if the email has changed since;</li>
 * <li>otherwise an account with the same email is linked to the identity. If that account's email was never
 * verified, someone may have registered the address with a password they know, so the password is removed, the
 * sessions revoked and the address marked verified;</li>
 * <li>otherwise a new verified account is created.</li>
 * </ol>
 * Disabled accounts never sign in. One transaction: a failure leaves nothing half done.
 */
@Service
@RequiredArgsConstructor
class SocialAccountResolver {

	/** What the provider told us, already reduced to what Archyra needs. */
	record ProviderIdentity(Provider provider, String providerUserId, String email, boolean emailVerified, String name) {
	}

	enum Outcome {
		EXISTING_IDENTITY, LINKED, CREATED
	}

	record Resolution(User user, Outcome outcome) {
	}

	private static final int MAX_NAME_LENGTH = 100;

	private final UserService userService;
	private final RefreshTokenService refreshTokens;
	private final SecurityAuditLogger audit;
	private final Clock clock;

	@Transactional
	Resolution resolve(ProviderIdentity identity) {
		if (!identity.emailVerified() || identity.email() == null || identity.email().isBlank()) {
			throw new SocialLoginException(SocialLoginErrors.EMAIL_UNVERIFIED,
					identity.provider() + " did not report a verified email");
		}
		String email = UserService.normalizeEmail(identity.email());

		Optional<LinkedIdentity> linked = userService.findIdentity(identity.provider(), identity.providerUserId());
		if (linked.isPresent()) {
			User user = userService.findById(linked.get().getUserId())
					.orElseThrow(() -> new SocialLoginException(SocialLoginErrors.FAILED, "linked user is missing"));
			requireEnabled(user);
			return new Resolution(user, Outcome.EXISTING_IDENTITY);
		}

		Optional<User> sameEmail = userService.findByEmail(email);
		if (sameEmail.isPresent()) {
			User user = sameEmail.get();
			requireEnabled(user);
			if (userService.linkedProviders(user.getId()).contains(identity.provider())) {
				// The account is already tied to another account of this provider; the address may have changed hands.
				throw new SocialLoginException(SocialLoginErrors.FAILED,
						"user already has a different " + identity.provider() + " identity");
			}
			userService.link(user, identity.provider(), identity.providerUserId(), email);
			if (!user.isEmailVerified()) {
				userService.takeOverUnverifiedAccount(user.getId(), clock.instant());
				refreshTokens.revokeAllForUser(user.getId());
				audit.warn("OAUTH_TAKEOVER_UNVERIFIED_" + identity.provider(), user.getId(), null);
			}
			audit.event("OAUTH_LINKED_" + identity.provider(), user.getId(), null);
			return new Resolution(userService.findById(user.getId()).orElse(user), Outcome.LINKED);
		}

		User created = userService.createSocialUser(email, displayName(identity.name(), email));
		userService.link(created, identity.provider(), identity.providerUserId(), email);
		audit.event("OAUTH_USER_CREATED_" + identity.provider(), created.getId(), null);
		return new Resolution(created, Outcome.CREATED);
	}

	private static void requireEnabled(User user) {
		if (!user.isEnabled()) {
			throw new SocialLoginException(SocialLoginErrors.ACCOUNT_DISABLED, "account is disabled");
		}
	}

	/** The provider's name, or the part of the email before the {@code @}, cut to the column size. */
	private static String displayName(String providerName, String email) {
		String name = providerName == null ? "" : providerName.replaceAll("\\p{Cntrl}", " ").trim();
		if (name.isEmpty()) {
			name = email.substring(0, Math.max(1, email.indexOf('@')));
		}
		return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
	}
}
