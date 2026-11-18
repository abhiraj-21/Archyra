package com.abhiraj.archyra.security;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Security events as structured log lines on the {@code security.audit} logger: event, user id and client IP.
 * Never pass passwords, tokens, token hashes or provider access tokens here.
 */
@Component
public class SecurityAuditLogger {

	private static final Logger AUDIT = LoggerFactory.getLogger("security.audit");

	public void event(String event, UUID userId, String ip) {
		AUDIT.info("event={} userId={} ip={}", event, userId, ip);
	}

	/** An action by one user on another (an administrator disabling an account): {@code userId} is the target. */
	public void event(String event, UUID userId, String ip, UUID actorId) {
		AUDIT.info("event={} userId={} actor={} ip={}", event, userId, actorId, ip);
	}

	/** An event with one more fact, a fixed word such as the case of a registration (never free text from a request). */
	public void eventWithCase(String event, UUID userId, String ip, String caseName) {
		AUDIT.info("event={} userId={} case={} ip={}", event, userId, caseName, ip);
	}

	public void warn(String event, UUID userId, String ip) {
		AUDIT.warn("event={} userId={} ip={}", event, userId, ip);
	}
}
