package com.abhiraj.archyra.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.abhiraj.archyra.analysis.AnalysisPurgeService;
import com.abhiraj.archyra.analysis.AnalysisRetention;
import com.abhiraj.archyra.mail.BillingMails;
import com.abhiraj.archyra.mail.EmailService;
import com.abhiraj.archyra.usage.UsageService;
import com.abhiraj.archyra.usage.UsageType;
import com.abhiraj.archyra.user.Plan;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What happens around the end of a Premium period, every {@code archyra.billing.sweep-interval}, on the injected {@link Clock}.
 * It covers any user with {@code plan = PREMIUM} and an end date (an administrator's grant included); Premium without an end
 * never lapses. Users whose subscription will charge again (live and not cancelled; a halted one does not count) are left
 * alone: their charge moves the end forward.
 * <ol>
 * <li><b>Reminder</b>, {@code reminder-days} before the end: once per user and end date.</li>
 * <li><b>Expired</b>, once the end has passed: the user is FREE already ({@code User.effectivePlan}); the email gives the exact
 *     time the repositories beyond the kept ones will be deleted.</li>
 * <li><b>Enforcement</b>, {@code grace-days} after the end (after the expiry email, if that went out late, see
 *     {@link BillingService#graceEndsAt}): in one transaction the user row is locked and the end <b>read
 *     again</b> (a renewal that landed meanwhile stops it), the repositories to delete are chosen
 *     ({@link AnalysisRetention}, the same ranking the warnings showed) and purged without a refund, and the plan goes to FREE
 *     with no end. After the commit the graphs are dropped (outside any transaction; what fails is retried by the purge sweep)
 *     and, when something was deleted, the user is told.</li>
 * </ol>
 * Every step is claimed by inserting its {@code billing_notices} row first; only the inserter acts, so the job is safe to run
 * twice or on two nodes at once. A crash between the claim's commit and the email loses that email rather than sending two.
 * Each user is handled on their own: one failure never stops the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PremiumLifecycleJob {

	/** What one run did, for the tests and the log. */
	record Run(int reminders, int expired, int enforced, int deleted) {
	}

	private final BillingProperties properties;
	private final BillingService billing;
	private final SubscriptionService subscriptions;
	private final SubscriptionRepository subscriptionRows;
	private final BillingNoticeRepository notices;
	private final UserService users;
	private final UsageService usage;
	private final AnalysisRetention retention;
	private final AnalysisPurgeService purge;
	private final EmailService email;
	private final TransactionTemplate transactions;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${archyra.billing.sweep-interval:15m}", initialDelayString = "${archyra.billing.sweep-interval:15m}")
	void sweep() {
		Run run = runOnce();
		if (run.reminders() + run.expired() + run.enforced() > 0) {
			log.info("Premium lifecycle: {} reminders, {} expiry notices, {} grace periods ended ({} repositories deleted)",
					run.reminders(), run.expired(), run.enforced(), run.deleted());
		}
	}

	Run runOnce() {
		Instant now = clock.instant();
		int reminders = 0;
		int expired = 0;
		int enforced = 0;
		int deleted = 0;

		List<UserService.PlanEnd> ending = users.premiumEndingBetween(now, billing.reminderFrom(now));
		Set<UUID> renewing = autoRenewing(ending);
		for (UserService.PlanEnd plan : ending) {
			if (!renewing.contains(plan.userId()) && guarded("remind", () -> remind(plan))) {
				reminders++;
			}
		}

		List<UserService.PlanEnd> ended = users.premiumEndedBy(now);
		Set<UUID> stillRenewing = autoRenewing(ended);
		for (UserService.PlanEnd plan : ended) {
			if (stillRenewing.contains(plan.userId())) {
				continue;
			}
			if (guarded("send the expiry notice", () -> expire(plan))) {
				expired++;
			}
			if (!billing.graceEndsAt(plan.userId(), plan.end()).isAfter(now)) {
				int[] count = { -1 };
				if (guarded("end the grace period", () -> {
					count[0] = enforce(plan, now);
					return count[0] >= 0;
				})) {
					enforced++;
					deleted += count[0];
				}
			}
		}

		try {
			subscriptions.cancelOrphans();
		}
		catch (RuntimeException ex) {
			log.warn("Could not retry the cancellation of orphaned subscriptions ({})", ex.getClass().getSimpleName());
		}
		return new Run(reminders, expired, enforced, deleted);
	}

	private Set<UUID> autoRenewing(List<UserService.PlanEnd> plans) {
		if (plans.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(subscriptionRows.autoRenewingAmong(plans.stream().map(UserService.PlanEnd::userId).toList()));
	}

	private boolean guarded(String what, java.util.function.BooleanSupplier step) {
		try {
			return step.getAsBoolean();
		}
		catch (RuntimeException ex) {
			log.warn("Could not {} for one user; the next sweep retries ({})", what, ex.getClass().getSimpleName());
			return false;
		}
	}

	// ---- 1. reminder --------------------------------------------------------------------------------------------

	private boolean remind(UserService.PlanEnd plan) {
		return Boolean.TRUE.equals(transactions.execute(status -> {
			if (notices.claim(plan.userId(), NoticeKind.REMINDER.name(), plan.end(), clock.instant()) == 0) {
				return false;
			}
			Optional<User> user = users.findById(plan.userId());
			if (user.isEmpty()) {
				return false;
			}
			BillingMails.PremiumEnding mail = ending(plan);
			String address = user.get().getEmail();
			String name = user.get().getName();
			AfterCommit.run("send the Premium reminder", () -> email.sendPremiumReminder(address, name, mail));
			return true;
		}));
	}

	// ---- 2. expired ---------------------------------------------------------------------------------------------

	private boolean expire(UserService.PlanEnd plan) {
		return Boolean.TRUE.equals(transactions.execute(status -> {
			if (notices.claim(plan.userId(), NoticeKind.EXPIRED.name(), plan.end(), clock.instant()) == 0) {
				return false;
			}
			Optional<User> user = users.findById(plan.userId());
			if (user.isEmpty()) {
				return false;
			}
			BillingMails.PremiumEnding mail = ending(plan);
			String address = user.get().getEmail();
			String name = user.get().getName();
			AfterCommit.run("send the Premium expiry notice", () -> email.sendPremiumExpired(address, name, mail));
			return true;
		}));
	}

	private BillingMails.PremiumEnding ending(UserService.PlanEnd plan) {
		List<AnalysisRetention.Item> atRisk = retention.rank(plan.userId(), properties.keepRepositories()).atRisk();
		return new BillingMails.PremiumEnding(plan.end(), billing.graceEndsAt(plan.userId(), plan.end()), properties.zoneId(), properties.keepRepositories(),
				atRisk.stream().limit(BillingMails.MAX_LISTED).map(item -> repositoryName(item.repositoryUrl())).toList(), atRisk.size(),
				billing.link("/upgrade"));
	}

	// ---- 3. enforcement ----------------------------------------------------------------------------------------------

	/** What the enforcement transaction leaves for after its commit. */
	private record Enforced(List<UUID> purged, List<String> kept, String email, String name) {
	}

	/** @return how many repositories were deleted, or -1 when nothing was done (renewed, or another run did it) */
	private int enforce(UserService.PlanEnd plan, Instant now) {
		Enforced done = transactions.execute(status -> {
			Optional<User> locked = users.lockForUpdate(plan.userId());
			if (locked.isEmpty()) {
				return null;
			}
			User user = locked.get();
			// read again under the lock: a renewal that committed since the candidates were listed moved (or cleared) the end
			if (user.getPlan() != Plan.PREMIUM || user.getPlanExpiresAt() == null || !user.getPlanExpiresAt().equals(plan.end())
					|| billing.graceEndsAt(user.getId(), user.getPlanExpiresAt()).isAfter(now)) {
				return null;
			}
			if (notices.claim(user.getId(), NoticeKind.DELETED.name(), plan.end(), clock.instant()) == 0) {
				return null;
			}
			// serialise with anything that starts an analysis of this user (the registrar takes the same lock)
			usage.lock(user.getId(), UsageType.REPOSITORY_ANALYSIS);
			AnalysisRetention.Retention ranked = retention.rank(user.getId(), properties.keepRepositories());
			List<UUID> purged = purge.purgeSelected(user.getId(), ranked.atRisk().stream().map(AnalysisRetention.Item::analysisId).toList());
			// the purge's listeners run clearing queries that detach entities: read the row again (the lock is held) before changing it
			users.lockForUpdate(user.getId()).orElseThrow().changePlan(Plan.FREE, null);
			log.info("Grace period of user {} ended: {} repositories deleted, plan back to FREE", user.getId(), purged.size());
			return new Enforced(purged, ranked.kept().stream().map(item -> repositoryName(item.repositoryUrl())).toList(), user.getEmail(),
					user.getName());
		});
		if (done == null) {
			return -1;
		}
		// Neo4j is outside the PostgreSQL transaction: drop the graphs only now (never throws; the purge sweep retries)
		purge.dropGraphs(done.purged());
		if (!done.purged().isEmpty()) {
			try {
				email.sendRepositoriesDeleted(done.email(), done.name(),
						new BillingMails.RepositoriesDeleted(done.purged().size(), done.kept(), billing.link("/upgrade")));
			}
			catch (RuntimeException ex) {
				log.warn("Could not send the repositories-deleted email ({})", ex.getClass().getSimpleName());
			}
		}
		return done.purged().size();
	}

	/** {@code owner/repo} of {@code https://github.com/owner/repo}: what the emails and the UI call a repository. */
	static String repositoryName(String url) {
		if (url == null) {
			return "";
		}
		String trimmed = url.replaceAll("/+$", "");
		String[] parts = trimmed.split("/");
		return parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1] : trimmed;
	}
}
