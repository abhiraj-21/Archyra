package com.abhiraj.archyra.mail;

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
