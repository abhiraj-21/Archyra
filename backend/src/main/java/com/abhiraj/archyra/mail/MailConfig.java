package com.abhiraj.archyra.mail;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * A small bounded pool for sending email. When the queue is full the message is dropped (and logged) instead of
 * running on the request thread: a slow SMTP server must not slow down, or change the timing of, a response.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
class MailConfig {

	static final String MAIL_EXECUTOR = EmailService.MAIL_EXECUTOR;

	@Bean(MAIL_EXECUTOR)
	Executor mailExecutor(
			@Value("${app.mail.executor.pool-size:2}") int poolSize,
			@Value("${app.mail.executor.queue-capacity:100}") int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix("mail-");
		RejectedExecutionHandler drop = (task, pool) -> log.warn("Mail queue is full; an email was dropped");
		executor.setRejectedExecutionHandler(drop);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(15);
		return executor;
	}
}
