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
}
