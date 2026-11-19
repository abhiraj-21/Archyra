package com.abhiraj.archyra.ai.llm;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Collects what the application logs while a test runs (every logger), so a test can assert on levels and on what is NOT in a log. */
public final class LogCapture implements AutoCloseable {

	private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Level before = root.getLevel();
	private final Logger retry = (Logger) LoggerFactory.getLogger("org.springframework.core.retry");
	private final Level retryBefore = retry.getLevel();

	public LogCapture() {
		root.setLevel(Level.DEBUG);
		retry.setLevel(Level.INFO); // as application.properties sets it: that logger would print a provider's error body
		appender.start();
		root.addAppender(appender);
	}

	public List<ILoggingEvent> events() {
		return List.copyOf(appender.list);
	}

	/** Every message with its arguments filled in, and the text of any exception, as one string per event. */
	public List<String> texts() {
		return events().stream().map(event -> event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
				: " " + ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()))).toList();
	}

	public boolean has(Level level, String contains) {
		return events().stream().anyMatch(event -> event.getLevel() == level && event.getFormattedMessage().contains(contains));
	}

	@Override
	public void close() {
		root.detachAppender(appender);
		root.setLevel(before);
		retry.setLevel(retryBefore);
		appender.stop();
	}
}
