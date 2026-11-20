package com.abhiraj.archyra.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.common.AdminRuleViolationException;
import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.common.UserNotFoundException;
import com.abhiraj.archyra.security.CurrentUser;
import com.abhiraj.archyra.security.SecurityAuditLogger;

import lombok.RequiredArgsConstructor;

/**
 * Account administration. Reaching the controller needs the ADMIN role in the token, but a token can outlive the
 * account that issued it, so every call also checks that the caller is, right now, an enabled administrator.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

	static final int MAX_PAGE_SIZE = 200;
	/** An account is "active" if it is enabled and signed in within this window. */
	static final Duration ACTIVE_WINDOW = Duration.ofDays(30);

	private final UserRepository userRepository;
	private final LinkedIdentityRepository identityRepository;
	private final ApplicationEventPublisher events;
	private final SecurityAuditLogger audit;
	private final AnalysisService analyses;
	private final Clock clock;

	/** @throws AccessDeniedException if the caller is not an enabled administrator */
	@Transactional(readOnly = true)
	public AdminUserPageResponse list(int page, int size) {
		requireActiveAdmin();
		if (page < 0) {
			throw new InvalidRequestException("page must not be negative");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		Page<User> result = userRepository.findAll(
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
		return new AdminUserPageResponse(result.getContent().stream().map(this::toResponse).toList(), page, size,
				result.getTotalElements(), result.getTotalPages());
	}

	/** Platform-wide counts, each one a database count query. */
	@Transactional(readOnly = true)
	public AdminStatsResponse stats() {
		requireActiveAdmin();
		Instant now = clock.instant();
		return new AdminStatsResponse(userRepository.count(),
				userRepository.countByEnabledTrueAndLastLoginAtGreaterThanEqual(now.minus(ACTIVE_WINDOW)),
				userRepository.countEffectivePremium(now), analyses.countAll());
	}

	@Transactional(readOnly = true)
	public AdminUserResponse get(String userId) {
		requireActiveAdmin();
		return toResponse(userRepository.findById(parse(userId)).orElseThrow(UserNotFoundException::new));
	}

	/**
	 * Enables or disables an account. Disabling ends all of the user's sessions (their refresh tokens), and is refused
	 * for the administrator's own account and for the last enabled administrator.
	 *
	 * @throws AdminRuleViolationException {@code CANNOT_DISABLE_SELF} or {@code LAST_ADMIN}
	 */
	@Transactional
	public AdminUserResponse setEnabled(String userId, boolean enabled, String ip) {
		UUID caller = requireActiveAdmin();
		UUID targetId = parse(userId);
		if (!enabled && targetId.equals(caller)) {
			throw new AdminRuleViolationException("CANNOT_DISABLE_SELF", "You cannot disable your own account");
		}
		// Lock the enabled admins first, then read the target: two admins disabling each other cannot both succeed.
		List<User> enabledAdmins = userRepository.lockEnabledByRole(Role.ADMIN);
		User target = userRepository.findById(targetId).orElseThrow(UserNotFoundException::new);
		if (!enabled && target.getRole() == Role.ADMIN && target.isEnabled() && enabledAdmins.size() <= 1) {
			throw new AdminRuleViolationException("LAST_ADMIN", "The last administrator cannot be disabled");
		}
		if (target.isEnabled() != enabled) {
			target.setEnabled(enabled);
			if (!enabled) {
				events.publishEvent(new UserDisabledEvent(target.getId()));
			}
			audit.event(enabled ? "USER_ENABLED" : "USER_DISABLED", target.getId(), ip, caller);
		}
		return toResponse(target);
	}

	/**
	 * Sets a user's plan by hand: a refund or chargeback (Razorpay never shortens Premium by itself), or a manual grant. The
	 * user row is locked, so this serialises with a payment being applied. PREMIUM may end at a future instant or never (null);
	 * FREE takes no end. Audited as {@code PLAN_CHANGED} with the administrator as actor.
	 *
	 * @throws InvalidRequestException for an end in the past, or an end with FREE
	 */
	@Transactional
	public AdminUserResponse setPlan(String userId, Plan plan, Instant expiresAt, String ip) {
		UUID caller = requireActiveAdmin();
		UUID targetId = parse(userId);
		Instant now = clock.instant();
		if (plan == Plan.FREE && expiresAt != null) {
			throw new InvalidRequestException("expiresAt must be empty for the FREE plan");
		}
		if (plan == Plan.PREMIUM && expiresAt != null && !expiresAt.isAfter(now)) {
			throw new InvalidRequestException("expiresAt must be in the future");
		}
		User target = userRepository.findByIdForUpdate(targetId).orElseThrow(UserNotFoundException::new);
		target.changePlan(plan, expiresAt);
		audit.event("PLAN_CHANGED", target.getId(), ip, caller);
		return toResponse(target);
	}

	/**
	 * The database check behind every admin route (the token's role is not trusted on its own): the caller must be an enabled
	 * administrator. Public so other features' admin endpoints re-check the same way.
	 *
	 * @throws AccessDeniedException if the caller is not an enabled administrator
	 */
	public UUID requireActiveAdmin() {
		UUID caller = CurrentUser.id().orElseThrow(() -> new AccessDeniedException("Not signed in"));
		boolean active = userRepository.findById(caller).filter(User::isEnabled).filter(user -> user.getRole() == Role.ADMIN).isPresent();
		if (!active) {
			throw new AccessDeniedException("Not an administrator");
		}
		return caller;
	}

	/** Anything that is not a canonical UUID cannot be a user. */
	private static UUID parse(String userId) {
		try {
			UUID id = UUID.fromString(userId);
			if (id.toString().equalsIgnoreCase(userId)) {
				return id;
			}
		}
		catch (IllegalArgumentException ex) {
			// falls through
		}
		throw new UserNotFoundException();
	}

	private AdminUserResponse toResponse(User user) {
		Instant now = clock.instant();
		List<Provider> providers = identityRepository.findByUserId(user.getId()).stream().map(LinkedIdentity::getProvider).sorted().toList();
		return new AdminUserResponse(user.getId().toString(), user.getName(), user.getEmail(), user.getRole(),
				user.effectivePlan(now), user.getPlanExpiresAt(), user.isEmailVerified(), user.isEnabled(), user.isLocked(now),
				user.getCreatedAt(), user.getLastLoginAt(), providers);
	}
}
