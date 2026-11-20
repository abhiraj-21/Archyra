package com.abhiraj.archyra.billing;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Premium through Razorpay ({@code archyra.billing.*}). The keys, the webhook secret and the plan ids come from the
 * environment and may be empty: billing is then simply off (every billing endpoint but the plans and the status answers
 * {@code 503 BILLING_UNAVAILABLE}) and the application still starts. Prices, the zone and the lifecycle numbers are
 * validated at startup. Test-mode and live-mode values differ in Razorpay; the property names are the same.
 *
 * @param razorpay         the Razorpay account: keys, webhook secret, plan ids, API base URL and timeout
 * @param prices           the amounts in paise and the currency; the client never sends an amount
 * @param zone             the IANA zone whose calendar months and years a period follows, and the zone of the dates in emails
 * @param reminderDays     how many days before the end the reminder is sent (and the UI says "Premium ends in ...")
 * @param graceDays        how many days after the end the repositories beyond {@code keepRepositories} are deleted
 * @param keepRepositories how many of the most recently used repositories a lapsed user keeps
 * @param sweepInterval    how often the lifecycle job runs
 * @param touchInterval    an analysis records "used now" at most once per this interval
 */
@Validated
@ConfigurationProperties(prefix = "archyra.billing")
public record BillingProperties(
		@Valid @DefaultValue Razorpay razorpay,
		@Valid @DefaultValue Prices prices,
		@DefaultValue("Asia/Kolkata") @NotBlank String zone,
		@DefaultValue("3") @Min(1) @Max(30) int reminderDays,
		@DefaultValue("3") @Min(0) @Max(90) int graceDays,
		@DefaultValue("5") @Min(1) @Max(1000) int keepRepositories,
		@DefaultValue("15m") @NotNull Duration sweepInterval,
		@DefaultValue("10m") @NotNull Duration touchInterval) {

	public BillingProperties {
		if (zone != null && !zone.isBlank()) {
			try {
				ZoneId.of(zone.trim());
			}
			catch (DateTimeException ex) {
				throw new IllegalArgumentException("archyra.billing.zone (BILLING_ZONE) is not a known time zone, use an IANA id such as Asia/Kolkata");
			}
		}
		if (sweepInterval != null && (sweepInterval.isNegative() || sweepInterval.isZero())) {
			throw new IllegalArgumentException("archyra.billing.sweep-interval must be positive");
		}
		if (touchInterval != null && touchInterval.isNegative()) {
			throw new IllegalArgumentException("archyra.billing.touch-interval must not be negative");
		}
		if (prices != null && prices.yearlyPaise() < prices.monthlyPaise()) {
			throw new IllegalArgumentException("archyra.billing.prices.yearly-paise must not be lower than monthly-paise");
		}
	}

	public ZoneId zoneId() {
		return ZoneId.of(zone.trim());
	}

	/** Orders, verification and cancelling need the key id and secret; nothing else does. */
	public boolean enabled() {
		return razorpay.hasKeys();
	}

	public long pricePaise(BillingPeriod period) {
		return switch (period) {
			case MONTHLY -> prices.monthlyPaise();
			case YEARLY -> prices.yearlyPaise();
		};
	}

	/**
	 * @param keyId         the public key id ({@code rzp_test_...} or {@code rzp_live_...}); sent to the browser
	 * @param keySecret     the API secret: HTTP Basic password and the HMAC key of checkout signatures; never leaves the server
	 * @param webhookSecret the HMAC key of webhook signatures; without it the webhook refuses everything
	 * @param planMonthly   the id of the monthly Razorpay Plan, needed only for auto-renew
	 * @param planYearly    the id of the yearly Razorpay Plan, needed only for auto-renew
	 * @param baseUrl       the API root; only tests point it elsewhere (a WireMock fake)
	 * @param timeout       connect and read timeout of one API call
	 */
	public record Razorpay(
			String keyId,
			String keySecret,
			String webhookSecret,
			String planMonthly,
			String planYearly,
			@DefaultValue("https://api.razorpay.com") @NotBlank String baseUrl,
			@DefaultValue("10s") @NotNull Duration timeout) {

		public boolean hasKeys() {
			return notBlank(keyId) && notBlank(keySecret);
		}

		public boolean hasWebhookSecret() {
			return notBlank(webhookSecret);
		}

		/** The Plan of a period, or null when auto-renew is not configured for it. */
		public String planId(BillingPeriod period) {
			String id = switch (period) {
				case MONTHLY -> planMonthly;
				case YEARLY -> planYearly;
			};
			return notBlank(id) ? id.trim() : null;
		}

		private static boolean notBlank(String value) {
			return value != null && !value.isBlank();
		}

		/** Never print a secret. */
		@Override
		public String toString() {
			return "Razorpay[keyId=" + (notBlank(keyId) ? "<set>" : "<unset>") + ", keySecret=<redacted>, webhookSecret=<redacted>, planMonthly="
					+ (notBlank(planMonthly) ? "<set>" : "<unset>") + ", planYearly=" + (notBlank(planYearly) ? "<set>" : "<unset>")
					+ ", baseUrl=" + baseUrl + ", timeout=" + timeout + "]";
		}
	}

	/** Inclusive of taxes. Razorpay wants the smallest currency unit, so paise for INR. */
	public record Prices(
			@DefaultValue("70000") @Min(100) long monthlyPaise,
			@DefaultValue("700000") @Min(100) long yearlyPaise,
			@DefaultValue("INR") @Pattern(regexp = "[A-Z]{3}", message = "archyra.billing.prices.currency must be a three-letter ISO code") String currency) {
	}
}
