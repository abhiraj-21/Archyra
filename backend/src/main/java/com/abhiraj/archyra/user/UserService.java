package com.abhiraj.archyra.user;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The entry point to accounts for every other feature. Later phases add registration, social-login linking,
 * profile updates and the admin operations here.
 */
@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final LinkedIdentityRepository linkedIdentityRepository;
	private final Clock clock;

	/** Emails are compared trimmed and lower-cased everywhere. */
	public static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	public Optional<User> findById(UUID id) {
		return userRepository.findById(id);
	}

	public Optional<User> findByEmail(String email) {
		return userRepository.findByEmail(normalizeEmail(email));
	}

	public User save(User user) {
		return userRepository.save(user);
	}

	public boolean adminExists() {
		return userRepository.existsByRole(Role.ADMIN);
	}

	public long countEnabledAdmins() {
		return userRepository.countByRoleAndEnabledTrue(Role.ADMIN);
	}

	public Optional<LinkedIdentity> findIdentity(Provider provider, String providerUserId) {
		return linkedIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId);
	}

	public List<Provider> linkedProviders(UUID userId) {
		return linkedIdentityRepository.findByUserId(userId).stream().map(LinkedIdentity::getProvider).sorted().toList();
	}

	public LinkedIdentity link(User user, Provider provider, String providerUserId, String providerEmail) {
		return linkedIdentityRepository.save(new LinkedIdentity(user.getId(), provider, providerUserId, providerEmail));
	}

	/**
	 * Creates a self-registered account, or returns empty if the email is already taken. Not transactional on
	 * purpose: a race on the unique email surfaces as an exception that is handled here, not rolled back around it.
	 */
	public Optional<User> registerIfAbsent(String email, String name, String passwordHash, Instant now) {
		String normalized = normalizeEmail(email);
		if (userRepository.findByEmail(normalized).isPresent()) {
			return Optional.empty();
		}
		try {
			return Optional.of(userRepository.saveAndFlush(User.registered(normalized, name, passwordHash, now)));
		}
		catch (DataIntegrityViolationException ex) {
			return Optional.empty();
		}
	}

	/** Counts a failed password attempt. Returns true if this one locked the account. */
	@Transactional
	public boolean recordFailedLogin(UUID userId, int maxAttempts, Duration lockDuration, Instant now) {
		User user = userRepository.findById(userId).orElse(null);
		if (user == null) {
			return false;
		}
		boolean wasLocked = user.isLocked(now);
		user.recordFailedLogin(maxAttempts, lockDuration, now);
		return !wasLocked && user.isLocked(now);
	}

	@Transactional
	public void recordSuccessfulLogin(UUID userId, Instant now) {
		userRepository.findById(userId).ifPresent(user -> user.recordSuccessfulLogin(now));
	}

	@Transactional
	public void markEmailVerified(UUID userId) {
		userRepository.findById(userId).ifPresent(User::markEmailVerified);
	}

	/** Sets a new password hash, records when, and clears any lockout. Returns false if the user does not exist. */
	@Transactional
	public boolean changePassword(UUID userId, String passwordHash, Instant now) {
		return userRepository.findById(userId).map(user -> {
			user.changePassword(passwordHash, now);
			user.resetLockout();
			return true;
		}).orElse(false);
	}

	/** A new account for a social login: verified, {@code USER}, {@code FREE}, no password. */
	@Transactional
	public User createSocialUser(String email, String name) {
		return userRepository.saveAndFlush(User.fromSocialLogin(normalizeEmail(email), name));
	}

	/**
	 * A social login takes over an account whose email was never verified: whoever registered it with a password
	 * may not be the owner of the address, so the password is removed and the address counts as verified.
	 */
	@Transactional
	public void takeOverUnverifiedAccount(UUID userId, Instant now) {
		userRepository.findById(userId).ifPresent(user -> {
			user.clearPassword(now);
			user.markEmailVerified();
			user.resetLockout();
		});
	}

	/** The user's own view of their account, or empty if it does not exist or was disabled. */
	public Optional<UserProfile> profile(UUID userId) {
		return userRepository.findById(userId).filter(User::isEnabled).map(this::toProfile);
	}

	/** Changes the display name and nothing else; email, role, plan and status are not editable here. */
	@Transactional
	public Optional<UserProfile> updateName(UUID userId, String name) {
		return userRepository.findById(userId).filter(User::isEnabled).map(user -> {
			user.rename(name.trim());
			return toProfile(user);
		});
	}

	private UserProfile toProfile(User user) {
		return new UserProfile(user.getId().toString(), user.getName(), user.getEmail(), user.getRole(),
				user.effectivePlan(clock.instant()), user.isEmailVerified(), linkedProviders(user.getId()));
	}
}
