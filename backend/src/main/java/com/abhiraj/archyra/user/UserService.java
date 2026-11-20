package com.abhiraj.archyra.user;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.analysis.AnalysisRestoreService;
import com.abhiraj.archyra.usage.UsageLedgerService;

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
	private final UsageLedgerService usageLedger;
	/**
	 * Looked up when needed, not injected: restoring entries goes through the analysis registrar, whose usage service reaches
	 * the entitlement service, which reads accounts through this class. A constructor dependency would be a bean cycle.
	 */
	private final ObjectProvider<AnalysisRestoreService> analysisRestore;
	private final TransactionTemplate transactions;
	private final Clock clock;

	/** Emails are compared trimmed and lower-cased everywhere. */
	public static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	public Optional<User> findById(UUID id) {
		return userRepository.findById(id);
	}

	/**
	 * Locks the account row until the caller's transaction ends, for code that changes the plan (billing): a payment confirmed
	 * twice at the same moment, or a renewal racing the end of a grace period, serialises on it.
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public Optional<User> lockForUpdate(UUID id) {
		return userRepository.findByIdForUpdate(id);
	}

	/** A Premium end date, for billing's lifecycle job. */
	public record PlanEnd(UUID userId, Instant end) {
	}

	/** Users whose PREMIUM plan ends after {@code from} and at or before {@code to}. Never one without an end. */
	@Transactional(readOnly = true)
	public List<PlanEnd> premiumEndingBetween(Instant from, Instant to) {
		return userRepository.findPremiumEndingBetween(from, to).stream().map(user -> new PlanEnd(user.getId(), user.getPlanExpiresAt())).toList();
	}

	/** Users whose stored plan is still PREMIUM although its end is at or before {@code now}. */
	@Transactional(readOnly = true)
	public List<PlanEnd> premiumEndedBy(Instant now) {
		return userRepository.findPremiumEndedBy(now).stream().map(user -> new PlanEnd(user.getId(), user.getPlanExpiresAt())).toList();
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
	 * The new account and the usage the ledger remembers for its email are written in one transaction of their own, so
	 * there is no moment when the account exists with zero counts (see {@link UsageLedgerService}).
	 */
	public Optional<User> registerIfAbsent(String email, String name, String passwordHash, Instant now) {
		String normalized = normalizeEmail(email);
		if (userRepository.findByEmail(normalized).isPresent()) {
			return Optional.empty();
		}
		try {
			return Optional.of(transactions.execute(status -> {
				User saved = userRepository.saveAndFlush(User.registered(normalized, name, passwordHash, now));
				restoreUsage(saved.getId(), Set.of(usageLedger.emailKey(normalized)));
				return saved;
			}));
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
	 * A new account for a first social sign-in, with its identity, and the usage the ledger remembers for the email and
	 * for that identity. Call inside the caller's transaction (the account resolver's), so account, link and restored
	 * counts commit together.
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public User createSocialUser(String email, String name, Provider provider, String providerUserId) {
		User created = userRepository.saveAndFlush(User.fromSocialLogin(normalizeEmail(email), name));
		linkedIdentityRepository.save(new LinkedIdentity(created.getId(), provider, providerUserId, normalizeEmail(email)));
		restoreUsage(created.getId(), usageLedger.keysFor(created.getEmail(),
				java.util.List.of(new UsageLedgerService.IdentityRef(provider, providerUserId))));
		return created;
	}

	/**
	 * An identity was attached to an existing account: its usage becomes the larger of the account's and what the ledger
	 * remembers for that identity (so "delete, register another email, link the old Google" restores the counts). Call
	 * inside the caller's transaction.
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void mergeLedgerUsage(UUID userId, Provider provider, String providerUserId) {
		restoreUsage(userId, Set.of(usageLedger.identityKey(provider, providerUserId)));
	}

	/**
	 * What a returning person gets back, in the caller's transaction: the counts first (only ever raised), then the
	 * repositories those credits were spent on as not-analysed entries. Silent: nothing here is returned or logged.
	 */
	private void restoreUsage(UUID userId, Collection<String> identityKeys) {
		usageLedger.restoreInto(userId, identityKeys);
		analysisRestore.getObject().restoreFor(userId, identityKeys);
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
