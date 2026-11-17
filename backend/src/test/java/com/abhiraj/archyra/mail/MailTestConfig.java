package com.abhiraj.archyra.mail;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces SMTP with {@link RecordingEmailService} for tests that load the whole application. */
@TestConfiguration(proxyBeanMethods = false)
public class MailTestConfig {

	@Bean
	@Primary
	RecordingEmailService recordingEmailService() {
		return new RecordingEmailService();
	}
}
