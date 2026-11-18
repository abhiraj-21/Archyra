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
