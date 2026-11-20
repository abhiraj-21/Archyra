package com.abhiraj.archyra.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class BillingPropertiesTest {

	static BillingProperties.Razorpay razorpay(String baseUrl) {
		return new BillingProperties.Razorpay(FakeRazorpay.KEY_ID, FakeRazorpay.KEY_SECRET, FakeRazorpay.WEBHOOK_SECRET,
				FakeRazorpay.PLAN_MONTHLY, FakeRazorpay.PLAN_YEARLY, baseUrl, Duration.ofSeconds(2));
	}

	static BillingProperties properties(BillingProperties.Razorpay razorpay) {
		return new BillingProperties(razorpay, new BillingProperties.Prices(70000, 700000, "INR"), "Asia/Kolkata", 3, 3, 5,
				Duration.ofMinutes(15), Duration.ofMinutes(10));
	}

	@Test
	void billingIsOnOnlyWithTheKeyIdAndTheSecret() {
		assertThat(properties(razorpay("http://x")).enabled()).isTrue();
		assertThat(properties(new BillingProperties.Razorpay("", "", "", "", "", "http://x", Duration.ofSeconds(1))).enabled()).isFalse();
		assertThat(properties(new BillingProperties.Razorpay("id", " ", null, null, null, "http://x", Duration.ofSeconds(1))).enabled()).isFalse();
	}

	@Test
	void pricesAndPlansFollowThePeriod() {
		BillingProperties properties = properties(razorpay("http://x"));
		assertThat(properties.pricePaise(BillingPeriod.MONTHLY)).isEqualTo(70000);
		assertThat(properties.pricePaise(BillingPeriod.YEARLY)).isEqualTo(700000);
		assertThat(properties.razorpay().planId(BillingPeriod.YEARLY)).isEqualTo(FakeRazorpay.PLAN_YEARLY);
		assertThat(new BillingProperties.Razorpay("a", "b", "", "", " ", "http://x", Duration.ofSeconds(1)).planId(BillingPeriod.YEARLY)).isNull();
	}

	@Test
	void anUnknownZoneFailsStartup() {
		assertThatThrownBy(() -> new BillingProperties(razorpay("http://x"), new BillingProperties.Prices(70000, 700000, "INR"), "Mars/Olympus",
				3, 3, 5, Duration.ofMinutes(15), Duration.ofMinutes(10))).hasMessageContaining("archyra.billing.zone");
	}

	@Test
	void aYearCheaperThanAMonthOrANonPositiveSweepFailsStartup() {
		assertThatThrownBy(() -> new BillingProperties(razorpay("http://x"), new BillingProperties.Prices(70000, 100, "INR"), "UTC",
				3, 3, 5, Duration.ofMinutes(15), Duration.ofMinutes(10))).hasMessageContaining("yearly-paise");
		assertThatThrownBy(() -> new BillingProperties(razorpay("http://x"), new BillingProperties.Prices(70000, 700000, "INR"), "UTC",
				3, 3, 5, Duration.ZERO, Duration.ofMinutes(10))).hasMessageContaining("sweep-interval");
	}

	@Test
	void secretsAreNeverPrinted() {
		String printed = properties(razorpay("http://x")).toString();
		assertThat(printed).doesNotContain(FakeRazorpay.KEY_SECRET, FakeRazorpay.WEBHOOK_SECRET, FakeRazorpay.KEY_ID, FakeRazorpay.PLAN_MONTHLY);
	}
}
