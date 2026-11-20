package com.abhiraj.archyra.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;

import jakarta.mail.internet.MimeMessage;

/** Real SMTP delivery against an in-memory server. The {@code @Async} hop is not involved: it is a plain call. */
class SmtpEmailServiceTest {

	@RegisterExtension
	static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

	private SmtpEmailService service;

	@BeforeEach
	void setUp() {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost("localhost");
		sender.setPort(GREEN_MAIL.getSmtp().getPort());
		service = new SmtpEmailService(sender, new MailProperties("no-reply@archyra.test"));
	}

	@Test
	void theVerificationEmailCarriesTheLinkAndTheConfiguredSender() throws Exception {
		service.sendVerificationEmail("ada@example.com", "Ada", "http://localhost:5173/verify-email?token=abc123");

		MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
		assertThat(received).hasSize(1);
		assertThat(received[0].getAllRecipients()[0]).hasToString("ada@example.com");
		assertThat(received[0].getFrom()[0]).hasToString("no-reply@archyra.test");
		assertThat(received[0].getSubject()).isEqualTo("Verify your Archyra email address");
		String body = GreenMailUtil.getBody(received[0]);
		assertThat(body).contains("Hi Ada,", "http://localhost:5173/verify-email?token=abc123", "24 hours");
	}

	@Test
	void theResetEmailCarriesTheLink() throws Exception {
		service.sendPasswordResetEmail("ada@example.com", "Ada", "http://localhost:5173/reset-password?token=xyz");

		MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
		assertThat(message.getSubject()).isEqualTo("Reset your Archyra password");
		assertThat(GreenMailUtil.getBody(message)).contains("http://localhost:5173/reset-password?token=xyz", "30 minutes");
	}

	@Test
	void theAccountExistsNoticeOffersLoginAndResetAndNeverBlames() throws Exception {
		service.sendAccountExistsNotice("ada@example.com", "Ada", List.of(), "http://localhost:5173/login", "http://localhost:5173/forgot-password");

		MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
		assertThat(message.getSubject()).isEqualTo("You already have an Archyra account");
		String body = GreenMailUtil.getBody(message);
		assertThat(body).contains("Hi Ada,", "http://localhost:5173/login", "http://localhost:5173/forgot-password",
				"If this wasn't you, you can ignore this message; your account is unchanged.");
		assertThat(body).doesNotContain("token").doesNotContain("signed up with");
	}

	@Test
	void theNoticeToASocialOnlyAccountNamesTheProviders() throws Exception {
		service.sendAccountExistsNotice("ada@example.com", "Ada", List.of("Google", "GitHub"), "http://localhost:5173/login", "http://localhost:5173/forgot-password");

		String body = GreenMailUtil.getBody(GREEN_MAIL.getReceivedMessages()[0]);
		assertThat(body).contains("You signed up with Google and GitHub", "http://localhost:5173/login", "choose a password");
		assertThat(body).doesNotContain("token");
	}

	@Test
	void thePasswordChangedNoticeSaysWhenAndPointsToResetWithoutAToken() throws Exception {
		service.sendPasswordChangedNotice("ada@example.com", "Ada", java.time.Instant.parse("2026-11-19T10:15:00Z"),
				"http://localhost:5173/forgot-password");

		MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
		assertThat(message.getSubject()).isEqualTo("Your Archyra password was changed");
		String body = GreenMailUtil.getBody(message);
		assertThat(body).contains("Hi Ada,", "19 Nov 2026, 10:15 UTC", "http://localhost:5173/forgot-password", "If this wasn't you");
		assertThat(body).doesNotContain("token");
	}

	@Test
	void aNameCannotInjectLinesIntoTheMessage() {
		service.sendVerificationEmail("ada@example.com", "Ada\r\nBcc: victim@example.com", "http://x/y?token=t");

		String body = GreenMailUtil.getBody(GREEN_MAIL.getReceivedMessages()[0]);
		assertThat(body).contains("Hi Ada  Bcc: victim@example.com,");
	}

	@Test
	void aDeadServerDoesNotMakeTheCallFail() {
		JavaMailSenderImpl broken = new JavaMailSenderImpl();
		broken.setHost("localhost");
		broken.setPort(1); // nothing listens here
		SmtpEmailService failing = new SmtpEmailService(broken, new MailProperties("no-reply@archyra.test"));

		assertThatCode(() -> failing.sendVerificationEmail("ada@example.com", "Ada", "http://x/y?token=t"))
				.doesNotThrowAnyException();
	}

	// ---- Premium (spec/razorpay-premium) --------------------------------------------------------------------------

	private static final java.time.ZoneId KOLKATA = java.time.ZoneId.of("Asia/Kolkata");

	@Test
	void thePremiumEmailGivesTheExactEndInTheBillingZoneWithItsLabel() throws Exception {
		service.sendPremiumActivated("ada@example.com", "Ada", new BillingMails.PremiumActivated("monthly", "700 INR",
				java.time.Instant.parse("2026-11-27T06:28:00Z"), KOLKATA, false, "http://localhost:5173/settings#subscription"));

		MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
		assertThat(message.getSubject()).isEqualTo("Archyra Premium is active");
		String body = GreenMailUtil.getBody(message);
		assertThat(body).contains("Hi Ada,", "monthly Premium payment of 700 INR", "Friday 27 November 2026, 11:58 IST",
				"does not renew by itself", "http://localhost:5173/settings#subscription");
	}

	@Test
	void theReminderNamesTheRepositoriesAtRiskCappedAtTenWithAndMore() throws Exception {
		List<String> twelve = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(i -> "example/repo-" + i).toList();
		service.sendPremiumReminder("ada@example.com", "Ada", new BillingMails.PremiumEnding(java.time.Instant.parse("2026-11-27T06:28:00Z"),
				java.time.Instant.parse("2026-11-30T06:28:00Z"), KOLKATA, 5, twelve.subList(0, 10), 12, "http://localhost:5173/upgrade"));

		MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
		assertThat(message.getSubject()).isEqualTo("Your Archyra Premium ends on Friday 27 November 2026, 11:58 IST");
		String body = GreenMailUtil.getBody(message);
		assertThat(body).contains("ends on Friday 27 November 2026, 11:58 IST", "On Monday 30 November 2026, 11:58 IST only your 5 most recently used",
				"- example/repo-1", "- example/repo-10", "and 2 more", "http://localhost:5173/upgrade");
		assertThat(body).doesNotContain("example/repo-11");
	}

	@Test
	void aReminderToSomeoneWithFiveOrFewerRepositoriesSaysNothingAboutDeleting() throws Exception {
		service.sendPremiumReminder("ada@example.com", "Ada", new BillingMails.PremiumEnding(java.time.Instant.parse("2026-11-27T06:28:00Z"),
				java.time.Instant.parse("2026-11-30T06:28:00Z"), KOLKATA, 5, List.of(), 0, "http://localhost:5173/upgrade"));

		String body = GreenMailUtil.getBody(GREEN_MAIL.getReceivedMessages()[0]);
		assertThat(body).doesNotContain("delete", "Monday 30 November");
	}

	@Test
	void theExpiryNoticeSaysWhenToRenewByAndTheDeletedNoticeWhatWasKept() throws Exception {
		service.sendPremiumExpired("ada@example.com", "Ada", new BillingMails.PremiumEnding(java.time.Instant.parse("2026-11-27T06:28:00Z"),
				java.time.Instant.parse("2026-11-30T06:28:00Z"), KOLKATA, 5, List.of("example/old"), 1, "http://localhost:5173/upgrade"));
		service.sendRepositoriesDeleted("ada@example.com", "Ada", new BillingMails.RepositoriesDeleted(7,
				List.of("example/a", "example/b", "example/c", "example/d", "example/e"), "http://localhost:5173/upgrade"));

		MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
		assertThat(received[0].getSubject()).isEqualTo("Your Archyra Premium has ended");
		assertThat(GreenMailUtil.getBody(received[0])).contains("ended on Friday 27 November 2026, 11:58 IST",
				"Renew by Monday 30 November 2026, 11:58 IST", "- example/old");
		assertThat(received[1].getSubject()).isEqualTo("Repositories were removed from your Archyra account");
		assertThat(GreenMailUtil.getBody(received[1])).contains("7 repositories were deleted", "No credits were returned", "- example/a", "- example/e");
	}
}
