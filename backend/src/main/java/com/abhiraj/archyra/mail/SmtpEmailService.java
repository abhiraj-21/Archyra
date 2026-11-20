package com.abhiraj.archyra.mail;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Plain-text email over SMTP, sent on the {@code mailExecutor} pool. */
@Slf4j
@Service
@RequiredArgsConstructor
class SmtpEmailService implements EmailService {

	private final JavaMailSender mailSender;
	private final MailProperties properties;

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendVerificationEmail(String to, String name, String link) {
		send(to, "Verify your Archyra email address", """
				Hi %s,

				Confirm your email address to finish setting up your Archyra account:

				%s

				The link works once and expires in 24 hours. If you did not create an account, ignore this email.
				""".formatted(greeting(name), link));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendPasswordResetEmail(String to, String name, String link) {
		send(to, "Reset your Archyra password", """
				Hi %s,

				Use this link to choose a new Archyra password:

				%s

				The link works once and expires in 30 minutes. If you did not ask for this, ignore this email;
				your password has not changed.
				""".formatted(greeting(name), link));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendAccountExistsNotice(String to, String name, List<String> providers, String loginLink, String resetLink) {
		String how = providers.isEmpty()
				? "If it was you, sign in here:\n\n%s\n\nForgot your password? You can choose a new one here:\n\n%s".formatted(loginLink, resetLink)
				: "You signed up with %s. If it was you, use that button on the sign-in page:\n\n%s\n\nYou can also choose a password for this account here:\n\n%s"
						.formatted(String.join(" and ", providers), loginLink, resetLink);
		send(to, "You already have an Archyra account", """
				Hi %s,

				Someone asked to create an Archyra account with this email address, and you already have one.

				%s

				If this wasn't you, you can ignore this message; your account is unchanged.
				""".formatted(greeting(name), how));
	}

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm 'UTC'", Locale.ENGLISH)
			.withZone(ZoneOffset.UTC);

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendIdentityLinkedNotice(String to, String name, String provider, Instant at, String resetLink) {
		send(to, provider + " was linked to your Archyra account", """
				Hi %s,

				%s was linked to your Archyra account on %s. You can now sign in with it.

				If this wasn't you, reset your password and review your sign-in methods in Settings:

				%s
				""".formatted(greeting(name), provider, WHEN.format(at), resetLink));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendIdentityUnlinkedNotice(String to, String name, String provider, Instant at, String resetLink) {
		send(to, provider + " was removed from your Archyra account", """
				Hi %s,

				%s was removed from your Archyra account on %s. You can no longer sign in with it.

				If this wasn't you, reset your password right away:

				%s
				""".formatted(greeting(name), provider, WHEN.format(at), resetLink));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendPasswordChangedNotice(String to, String name, Instant at, String resetLink) {
		send(to, "Your Archyra password was changed", """
				Hi %s,

				The password of your Archyra account was changed on %s. Your other devices were signed out.

				If this wasn't you, reset your password right away:

				%s
				""".formatted(greeting(name), WHEN.format(at), resetLink));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendAccountDeletionRequest(String to, String name, String link) {
		send(to, "Confirm deleting your Archyra account", """
				Hi %s,

				You asked to delete your Archyra account. Open this link to confirm. It permanently deletes your analyses,
				graphs and Ask AI conversations:

				%s

				We keep your usage counts and the links of the repositories you had analysed, so they can be restored if you
				come back. No code, graph or conversation is kept.

				The link works once and expires in 15 minutes. If you did not ask for this, ignore this email and
				your account stays as it is.
				""".formatted(greeting(name), link));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendAccountDeletedNotice(String to, String name, Instant at) {
		send(to, "Your Archyra account was deleted", """
				Hi %s,

				Your Archyra account was deleted on %s, together with your analyses, graphs and Ask AI conversations.

				Your usage counts and the links of the repositories you had analysed are kept so they can be restored if you
				come back. No code, graph or conversation is kept.

				If this wasn't you, contact us right away.
				""".formatted(greeting(name), WHEN.format(at)));
	}

	/** "Friday 27 November 2026, 11:58 IST": the exact time in the billing zone, with the zone's label. */
	static String exactTime(Instant at, java.time.ZoneId zone) {
		return DateTimeFormatter.ofPattern("EEEE d MMMM uuuu, HH:mm zzz", Locale.ENGLISH).withZone(zone).format(at);
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendPremiumActivated(String to, String name, BillingMails.PremiumActivated mail) {
		String renewal = mail.autoRenew()
				? "It renews automatically at the end of each period. You can cancel auto-renew at any time in Settings; Premium then stays until the end of the period you paid for."
				: "It does not renew by itself. We will remind you a few days before it ends.";
		send(to, "Archyra Premium is active", """
				Hi %s,

				Thank you. Your %s Premium payment of %s went through.

				Premium is active until %s.

				%s

				Manage your plan:

				%s
				""".formatted(greeting(name), mail.period(), mail.amount(), exactTime(mail.expiresAt(), mail.zone()), renewal, mail.manageLink()));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendPremiumReminder(String to, String name, BillingMails.PremiumEnding mail) {
		send(to, "Your Archyra Premium ends on " + exactTime(mail.endsAt(), mail.zone()), """
				Hi %s,

				Your Archyra Premium ends on %s. It does not renew by itself.
				%s
				Renew now to keep Premium:

				%s
				""".formatted(greeting(name), exactTime(mail.endsAt(), mail.zone()), atRisk(mail), mail.renewLink()));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendPremiumExpired(String to, String name, BillingMails.PremiumEnding mail) {
		String keep = mail.atRiskTotal() > 0
				? "Renew by " + exactTime(mail.deletionAt(), mail.zone()) + " to keep all of your repositories."
				: "Renew whenever you like; your repositories are not affected.";
		send(to, "Your Archyra Premium has ended", """
				Hi %s,

				Your Archyra Premium ended on %s. Your account is on the Free plan now.

				%s
				%s
				Renew Premium:

				%s
				""".formatted(greeting(name), exactTime(mail.endsAt(), mail.zone()), keep, atRisk(mail), mail.renewLink()));
	}

	@Async(MailConfig.MAIL_EXECUTOR)
	@Override
	public void sendRepositoriesDeleted(String to, String name, BillingMails.RepositoriesDeleted mail) {
		send(to, "Repositories were removed from your Archyra account", """
				Hi %s,

				Your Archyra Premium ended and was not renewed during the grace period, so your account kept only its %d most recently used repositories. %d %s deleted, together with their graphs and Ask AI conversations. No credits were returned.

				Kept:
				%s

				Premium is available again at any time:

				%s
				""".formatted(greeting(name), mail.kept().size(), mail.deletedCount(), mail.deletedCount() == 1 ? "repository was" : "repositories were",
				bullets(mail.kept(), mail.kept().size()), mail.renewLink()));
	}

	/** Empty when nothing would be deleted; otherwise the deletion time and the names, cut at ten. */
	private static String atRisk(BillingMails.PremiumEnding mail) {
		if (mail.atRiskTotal() <= 0) {
			return "";
		}
		return """

				On %s only your %d most recently used repositories are kept. These would be deleted, with their graphs and Ask AI conversations:
				%s
				""".formatted(exactTime(mail.deletionAt(), mail.zone()), mail.keep(), bullets(mail.atRisk(), mail.atRiskTotal()));
	}

	private static String bullets(List<String> names, int total) {
		StringBuilder text = new StringBuilder();
		names.stream().limit(BillingMails.MAX_LISTED).forEach(repository -> text.append("- ").append(oneLine(repository)).append('\n'));
		int more = total - (int) Math.min(names.size(), BillingMails.MAX_LISTED);
		if (more > 0) {
			text.append("and ").append(more).append(" more\n");
		}
		return text.toString().stripTrailing();
	}

	private static String oneLine(String value) {
		return value == null ? "" : value.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
	}

	private void send(String to, String subject, String text) {
		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(properties.from());
			message.setTo(to);
			message.setSubject(subject);
			message.setText(text);
			mailSender.send(message);
		}
		catch (RuntimeException ex) {
			// Never rethrow and never log the address or the link: nothing may depend on, or reveal, the outcome.
			log.warn("Could not send a \"{}\" email ({})", subject, ex.getClass().getSimpleName());
			log.debug("Mail failure detail", ex);
		}
	}

	/** Names come from users; keep only a single printable line. */
	private static String greeting(String name) {
		String clean = name == null ? "" : name.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
		return clean.isEmpty() ? "there" : clean;
	}
}
