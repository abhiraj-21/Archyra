package com.abhiraj.archyra.usage;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.user.Provider;

/**
 * Remembers what a deleted account had consumed, so deleting an account and registering again does not reset the free
 * allowances (see {@code spec/account-deletion/spec.md}). The ledger holds, per identity key, only the per-type
 * {@code used_count}; a key is a keyed hash of the normalised email or of {@code provider:providerUserId}.
 *
 * <p>Rules: counts only go up (a returning user gets the maximum over all their keys, and a merge never lowers a count);
 * the plan is not stored (a returning user is FREE); nothing here reveals to a caller whether a key matched; and no key,
 * hash or raw value is ever logged. Every method that writes needs the caller's transaction ({@code MANDATORY}) so a
 * user and the counts restored for them commit together, with no window of zero counts.
 *
 * <p>This is the only code outside {@link UsageService} that writes {@code user_usage}, and it lives here (not in
 * {@code UsageService}) so that {@code user/} can call it without a bean cycle through the entitlement service.
 */
@Service
public class UsageLedgerService {

	/** A social identity as the ledger sees it. */
	public record IdentityRef(Provider provider, String providerUserId) {
	}

	private final UsageLedgerRepository ledger;
	private final UserUsageRepository usage;
	private final LedgerKeys keys;

	UsageLedgerService(UsageLedgerRepository ledger, UserUsageRepository usage, UsageProperties properties) {
		this.ledger = ledger;
		this.usage = usage;
		this.keys = new LedgerKeys(properties.ledgerSecret());
	}

	/** The key of an email (normalised as in {@link LedgerKeys#normalizeEmail}) followed by one key per identity. */
	public Set<String> keysFor(String email, Collection<IdentityRef> identities) {
		Set<String> result = new LinkedHashSet<>();
		result.add(keys.emailKey(email));
		identities.forEach(identity -> result.add(keys.identityKey(identity.provider(), identity.providerUserId())));
		return result;
	}

	public String emailKey(String email) {
		return keys.emailKey(email);
	}

	public String identityKey(Provider provider, String providerUserId) {
		return keys.identityKey(provider, providerUserId);
	}

	/**
	 * Called inside the deletion transaction, before anything is removed: stores the user's current counts under every
	 * key, keeping the larger value where one exists already. Types the user never used are not stored.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(UUID userId, Collection<String> identityKeys) {
		for (UserUsage row : usage.findByUserId(userId)) {
			if (row.getUsedCount() <= 0) {
				continue;
			}
			for (String key : identityKeys) {
				ledger.raiseTo(UUID.randomUUID(), key, row.getUsageType().name(), row.getUsedCount());
			}
		}
	}

	/** The largest remembered count per usage type over all the keys. Types never recorded are absent. */
	@Transactional(readOnly = true)
	public Map<UsageType, Integer> restoreFor(Collection<String> identityKeys) {
		Map<UsageType, Integer> result = new EnumMap<>(UsageType.class);
		if (identityKeys.isEmpty()) {
			return result;
		}
		for (UsageLedgerEntry entry : ledger.findByIdentityKeyIn(identityKeys)) {
			UsageType type = typeOf(entry.getUsageType());
			if (type != null) {
				result.merge(type, entry.getUsedCount(), Math::max);
			}
		}
		return result;
	}

	/**
	 * Writes the remembered counts into the user's {@code user_usage}, only ever raising them. Used when a user is created
	 * (the keys of their email and identity) and when an identity is linked to an existing account (the new identity's
	 * key: a merge). Silent: nothing about the outcome is returned to a caller that could show it.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void restoreInto(UUID userId, Collection<String> identityKeys) {
		restoreFor(identityKeys).forEach((type, count) -> {
			if (count > 0) {
				usage.raiseTo(UUID.randomUUID(), userId, type.name(), count);
			}
		});
	}

	/** Removes the user's counters once their consumption is in the ledger. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void deleteCountersOf(UUID userId) {
		usage.deleteAllOf(userId);
	}

	/** Ignores a stored type this version does not know (a rolled-back deployment), instead of failing. */
	private static UsageType typeOf(String name) {
		try {
			return UsageType.valueOf(name);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
