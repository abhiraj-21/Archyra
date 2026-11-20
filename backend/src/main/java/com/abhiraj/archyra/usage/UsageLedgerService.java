package com.abhiraj.archyra.usage;

import java.time.Instant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
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
 * <p>Rules: a deleted account <b>overwrites</b> what its keys remember with its current state (counts, weekly window
 * and repository links), while a restore or a merge only ever raises counts (a returning user gets the maximum over all
 * their keys; a weekly count is remembered with its window and only restored in that same week: after the next Monday
 * it is 0). Besides counts the ledger keeps the canonical URLs of the repositories the deleted account had paid for, so
 * they can come back as not-analysed entries (see {@code AnalysisRestoreService}); never any analysed content;
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

	/** A repository the deleted account had paid an analysis for: its canonical URL and when that analysis was created. */
	public record RepositoryRef(String url, Instant createdAt) {

		/** A URL is never shown next to anything that identifies a person, so it stays out of every log line. */
		@Override
		public String toString() {
			return "RepositoryRef[]";
		}
	}

	private final UsageLedgerRepository ledger;
	private final RetainedRepositoryRepository retained;
	private final UserUsageRepository usage;
	private final LedgerKeys keys;
	private final UsageWindows windows;

	UsageLedgerService(UsageLedgerRepository ledger, RetainedRepositoryRepository retained, UserUsageRepository usage,
			UsageProperties properties, UsageWindows windows) {
		this.ledger = ledger;
		this.retained = retained;
		this.usage = usage;
		this.keys = new LedgerKeys(properties.ledgerSecret());
		this.windows = windows;
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
	 * Called inside the deletion transaction, before anything is removed: <b>overwrites</b> what every key remembers with
	 * the account's current state. Per type the stored count becomes the account's current {@code used_count} (the row is
	 * deleted when the account has none, a weekly row without a window counts as none); a weekly type is stored with the
	 * window its count belongs to. The key's repository set is replaced too: its old rows go and one row per given
	 * repository is inserted (a repository paid for twice is given twice). Overwriting, not taking the maximum, is what
	 * keeps "restore, delete an entry (a credit comes back), delete the account again" from bringing the old higher count
	 * back with the repository gone; the maximum rule belongs to restore and merge only.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(UUID userId, Collection<String> identityKeys, Collection<RepositoryRef> repositories) {
		Map<UsageType, UserUsage> rows = new EnumMap<>(UsageType.class);
		usage.findByUserId(userId).forEach(row -> rows.put(row.getUsageType(), row));
		for (String key : identityKeys) {
			for (UsageType type : UsageType.values()) {
				UserUsage row = rows.get(type);
				if (row == null || row.getUsedCount() <= 0 || (type.weekly() && row.getWindowStart() == null)) {
					ledger.deleteByKeyAndType(key, type.name());
				}
				else if (type.weekly()) {
					ledger.overwriteInWindow(UUID.randomUUID(), key, type.name(), row.getUsedCount(), row.getWindowStart());
				}
				else {
					ledger.overwrite(UUID.randomUUID(), key, type.name(), row.getUsedCount());
				}
			}
			retained.deleteByIdentityKey(key);
			retained.saveAll(repositories.stream().map(repository -> new RetainedRepository(key, repository.url(), repository.createdAt())).toList());
		}
	}

	/**
	 * The largest remembered count per usage type over all the keys. Types never recorded are absent. A weekly type only
	 * counts entries of the current window: an entry of an earlier week (or without a window) restores nothing.
	 */
	@Transactional(readOnly = true)
	public Map<UsageType, Integer> restoreFor(Collection<String> identityKeys) {
		return restoreFor(identityKeys, windows.currentStart());
	}

	private Map<UsageType, Integer> restoreFor(Collection<String> identityKeys, Instant currentWindow) {
		Map<UsageType, Integer> result = new EnumMap<>(UsageType.class);
		if (identityKeys.isEmpty()) {
			return result;
		}
		for (UsageLedgerEntry entry : ledger.findByIdentityKeyIn(identityKeys)) {
			UsageType type = typeOf(entry.getUsageType());
			if (type == null || (type.weekly() && (entry.getWindowStart() == null || entry.getWindowStart().isBefore(currentWindow)))) {
				continue;
			}
			result.merge(type, entry.getUsedCount(), Math::max);
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
		Instant currentWindow = windows.currentStart();
		restoreFor(identityKeys, currentWindow).forEach((type, count) -> {
			if (count > 0) {
				if (type.weekly()) {
					usage.raiseToInWindow(UUID.randomUUID(), userId, type.name(), count, currentWindow);
				}
				else {
					usage.raiseTo(UUID.randomUUID(), userId, type.name(), count);
				}
			}
		});
	}

	/** The user's stored count of a type (0 when there is no row), read from the database. */
	@Transactional(readOnly = true)
	public int usedCount(UUID userId, UsageType type) {
		return usage.findUsedCount(userId, type).orElse(0);
	}

	/**
	 * Which repositories come back as not-analysed entries for a user whose keys are given. The union over all the keys:
	 * for each URL the largest number of rows any <i>single</i> key holds (the same repository stored under the email key
	 * and a Google key is not doubled); minus what the account already holds (a repository it has now is never duplicated,
	 * one row per held analysis); the most recently created first; at most {@code limit}. Entries older than the limit
	 * simply stay spent as credits without a link.
	 *
	 * @param alreadyHeld the URLs of the analyses the account has already, one element per analysis
	 * @return the URLs to restore, newest first
	 */
	@Transactional(readOnly = true)
	public List<String> repositoriesToRestore(Collection<String> identityKeys, Collection<String> alreadyHeld, int limit) {
		if (limit <= 0 || identityKeys.isEmpty()) {
			return List.of();
		}
		Map<String, Map<String, List<Instant>>> perKey = new HashMap<>();
		for (RetainedRepository row : retained.findByIdentityKeyIn(identityKeys)) {
			perKey.computeIfAbsent(row.getIdentityKey(), key -> new HashMap<>())
					.computeIfAbsent(row.getRepositoryUrl(), url -> new ArrayList<>()).add(row.getCreatedAt());
		}
		Map<String, List<Instant>> best = new HashMap<>();
		perKey.values().forEach(urls -> urls.forEach((url, times) -> {
			if (times.size() > best.getOrDefault(url, List.of()).size()) {
				best.put(url, times);
			}
		}));
		Map<String, Integer> held = new HashMap<>();
		alreadyHeld.forEach(url -> held.merge(url, 1, Integer::sum));

		record Candidate(String url, Instant createdAt) {
		}
		List<Candidate> candidates = new ArrayList<>();
		best.forEach((url, times) -> {
			int missing = times.size() - held.getOrDefault(url, 0);
			times.stream().sorted(Comparator.reverseOrder()).limit(Math.max(missing, 0))
					.forEach(createdAt -> candidates.add(new Candidate(url, createdAt)));
		});
		return candidates.stream()
				.sorted(Comparator.comparing(Candidate::createdAt).reversed().thenComparing(Candidate::url))
				.limit(limit).map(Candidate::url).toList();
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
