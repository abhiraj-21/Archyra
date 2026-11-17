package com.abhiraj.archyra.security;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.common.PremiumRequiredException;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * The one place that answers "what may this user do?". The plan is read from the database on every check and
 * never from the access token, so an upgrade or an expiry applies at once. A missing or disabled user counts as
 * FREE. Later, usage limits and the billing webhook plug in here without touching controllers.
 *
 * <p>Style: services call {@link #requirePremium(Feature)} themselves; there is no SpEL variant.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

	private final UserService userService;
	private final Clock clock;

	public Plan effectivePlan(UUID userId) {
		return userService.findById(userId)
				.filter(user -> user.isEnabled())
				.map(user -> user.effectivePlan(clock.instant()))
				.orElse(Plan.FREE);
	}

	public boolean canUse(UUID userId, Feature feature) {
		return effectivePlan(userId) == Plan.PREMIUM;
	}

	/**
	 * @throws PremiumRequiredException if the caller is not on an active PREMIUM plan (also when nobody is signed in)
	 */
	public void requirePremium(Feature feature) {
		boolean allowed = CurrentUser.id().map(id -> canUse(id, feature)).orElse(false);
		if (!allowed) {
			throw new PremiumRequiredException(feature.displayName() + " requires Archyra Premium.");
		}
	}
}
