package com.abhiraj.archyra.user;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.analysis.AnalysisPurgeService;
import com.abhiraj.archyra.auth.AccountDataPurger;
import com.abhiraj.archyra.common.AccountDisabledException;
import com.abhiraj.archyra.common.AdminRuleViolationException;
import com.abhiraj.archyra.mail.EmailService;
import com.abhiraj.archyra.security.RevokedSubjects;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.usage.UsageLedgerService;
import com.abhiraj.archyra.usage.UsageService;
import com.abhiraj.archyra.usage.UsageType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes an account without resetting what it consumed (see {@code spec/account-deletion/spec.md}). The caller has
 * already <b>authorised</b> it (the password, or the emailed confirmation link, which live in {@code auth/}); this
 * does the rest in one transaction, in this order:
 * <ol>
 * <li>lock the user, refuse the last enabled administrator ({@code 409 LAST_ADMIN});</li>
 * <li>write the consumption into the usage ledger, <b>first</b>: the current count per type and the links of the
 *     repositories that still hold a credit (nothing else about them), <b>overwriting</b> what the account's keys held
 *     before, so nothing below can change it;</li>
 * <li>remove every analysis through {@link AnalysisPurgeService}, which never refunds a credit and, because the
 *     graph is in Neo4j (outside this transaction), records the ids and drops the graphs after the commit;</li>
 * <li>delete the refresh, one-time and link-intent tokens, the linked identities, the counters and the user.</li>
 * </ol>
 * After the commit ({@link #afterDeletion}, called by the caller outside any transaction): the account's access token
 * stops working, the graphs are dropped, an audit line (user id, no email) is written, an {@code AccountDeleted} event lets
 * billing cancel a live Razorpay subscription, and a "your account was deleted"
 * email goes to the address held before the deletion. None of those can fail the deletion. Only keyed hashes, counts and
 * repository links remain; nothing logged here names a key or an email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

	/** What {@link #afterDeletion} needs once the deletion has committed. Holds an address, so it is never logged. */
	public record Deleted(UUID userId, String email, String name, List<UUID> analysisIds, String ip, Instant at) {

		@Override
		public String toString() {
			return "Deleted[userId=" + userId + "]";
		}
	}

	private final UserRepository users;
	private final LinkedIdentityRepository identities;
	private final UsageLedgerService ledger;
	private final UsageService usage;
	private final AnalysisPurgeService analyses;
	private final AccountDataPurger tokens;
	private final EmailService emailService;
	private final RevokedSubjects revokedSubjects;
	private final SecurityAuditLogger audit;
	private final Clock clock;
	private final org.springframework.context.ApplicationEventPublisher events;

	/**
	 * @param ip the client address, for the audit line only
	 * @throws AuthenticationCredentialsNotFoundException if the account is already gone (a repeated call is a 401)
	 * @throws AccountDisabledException                   if the account is disabled
	 * @throws AdminRuleViolationException                {@code LAST_ADMIN}
	 * @throws com.abhiraj.archyra.common.AnalysisInProgressException if an analysis is still running
	 */
	@Transactional
	public Deleted delete(UUID userId, String ip) {
		User user = users.findByIdForUpdate(userId)
				.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("The account no longer exists"));
		if (!user.isEnabled()) {
			throw new AccountDisabledException();
		}
		if (user.getRole() == Role.ADMIN && users.lockEnabledByRole(Role.ADMIN).size() <= 1) {
			throw new AdminRuleViolationException("LAST_ADMIN", "The last administrator cannot delete their account");
		}

		String email = user.getEmail();
		String name = user.getName();
		List<LinkedIdentity> linked = identities.findByUserId(userId);

		// Serialize with a request that starts an analysis for this user, then record the counts before anything is touched.
		usage.lock(userId, UsageType.REPOSITORY_ANALYSIS);
		ledger.record(userId, ledger.keysFor(email, linked.stream()
				.map(identity -> new UsageLedgerService.IdentityRef(identity.getProvider(), identity.getProviderUserId())).toList()),
				analyses.retainableRepositories(userId));

		List<UUID> analysisIds = analyses.purgeAllOf(userId);
		tokens.deleteAllOf(userId);
		identities.deleteAll(linked);
		ledger.deleteCountersOf(userId);
		users.delete(user);
		users.flush();

		log.info("Account {} deleted ({} analyses)", userId, analysisIds.size());
		return new Deleted(userId, email, name, analysisIds, ip, clock.instant());
	}

	/**
	 * Call once the transaction of {@link #delete} has <b>committed</b> (outside any transaction: the Neo4j calls must not
	 * run inside a PostgreSQL transaction's completion). The account's access token stops working, the graphs are dropped
	 * (what cannot be is retried by the sweep), an audit line (user id, no email) is written and the notice goes to the
	 * address held before the deletion. Nothing here can fail the deletion.
	 */
	public void afterDeletion(Deleted deleted) {
		revokedSubjects.revoke(deleted.userId());
		analyses.dropGraphs(deleted.analysisIds());
		audit.event("ACCOUNT_DELETED", deleted.userId(), deleted.ip());
		try {
			events.publishEvent(new com.abhiraj.archyra.common.AccountDeleted(deleted.userId())); // billing: cancel a live subscription
		}
		catch (RuntimeException ex) {
			log.warn("A listener of the account deletion failed; the account is deleted ({})", ex.getClass().getSimpleName());
		}
		try {
			emailService.sendAccountDeletedNotice(deleted.email(), deleted.name(), deleted.at());
		}
		catch (RuntimeException ex) {
			log.warn("Could not send an account-deleted notice ({})", ex.getClass().getSimpleName());
		}
	}
}
