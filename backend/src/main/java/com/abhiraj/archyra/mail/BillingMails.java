package com.abhiraj.archyra.mail;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * What the four Premium emails say. Dates are written in {@code zone} with its label ("Friday 27 November 2026, 11:58 IST").
 * Repository names are {@code owner/repo}; never a token, a key, a payment id or an amount from a request.
 */
public final class BillingMails {

	private BillingMails() {
	}

	/**
	 * @param period    "monthly" or "annual"
	 * @param amount    the price as shown on the payment screen ("700 INR")
	 * @param autoRenew whether it renews by itself
	 * @param manageLink the Settings section where the plan is managed
	 */
	public record PremiumActivated(String period, String amount, Instant expiresAt, ZoneId zone, boolean autoRenew, String manageLink) {
	}

	/**
	 * The reminder before the end and the notice after it.
	 *
	 * @param keep        how many repositories are kept
	 * @param atRisk      the names of the repositories that would be deleted, at most {@link #MAX_LISTED}
	 * @param atRiskTotal how many would be deleted in all (0: nothing would be, and the email says nothing about deletion)
	 */
	public record PremiumEnding(Instant endsAt, Instant deletionAt, ZoneId zone, int keep, List<String> atRisk, int atRiskTotal,
			String renewLink) {
	}

	public record RepositoriesDeleted(int deletedCount, List<String> kept, String renewLink) {
	}

	/** Lists in emails stop here and say "and N more". */
	public static final int MAX_LISTED = 10;
}
