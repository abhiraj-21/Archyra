package com.abhiraj.archyra.auth;

import java.time.Clock;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the first administrator at startup when {@code ADMIN_EMAIL} and {@code ADMIN_PASSWORD} are set and no
 * administrator exists yet. Admins are never created through registration or social login, and an existing account
 * is never promoted. A password that would not be accepted at registration stops the startup, so a weak bootstrap
 * password cannot slip in. The password is never logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AdminBootstrapRunner implements ApplicationRunner {

	private final SecurityProperties properties;
	private final UserService userService;
	private final PasswordEncoder passwordEncoder;
	private final SecurityAuditLogger audit;
	private final Clock clock;

	@Override
	public void run(ApplicationArguments args) {
		var bootstrap = properties.bootstrapAdmin();
		if (!bootstrap.configured() || userService.adminExists()) {
			return;
		}
		String email = UserService.normalizeEmail(bootstrap.email());
		if (userService.findByEmail(email).isPresent()) {
			log.error("Not creating the bootstrap administrator: an account with that email already exists");
			return;
		}
		try {
			PasswordPolicy.check(bootstrap.password(), email);
		}
		catch (InvalidRequestException ex) {
			throw new IllegalStateException("app.security.bootstrap-admin.password is not acceptable: " + ex.getMessage());
		}
		User admin = userService.save(User.administrator(email, "Administrator", passwordEncoder.encode(bootstrap.password()),
				clock.instant()));
		audit.event("ADMIN_BOOTSTRAPPED", admin.getId(), null);
		log.warn("Created the bootstrap administrator account. Remove ADMIN_PASSWORD from the environment now.");
	}
}
