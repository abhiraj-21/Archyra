package com.abhiraj.archyra.security;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Refuses to start in a configuration that would weaken security in production. The JWT secret length is
 * already checked while {@link SecurityProperties} binds.
 */
@Slf4j
@Component
class SecurityStartupChecks {

	SecurityStartupChecks(SecurityProperties properties, Environment environment) {
		if (!properties.refreshToken().cookieSecure() && environment.acceptsProfiles(Profiles.of("prod"))) {
			throw new IllegalStateException(
					"app.security.refresh-token.cookie-secure must be true when the prod profile is active");
		}
		if (!properties.refreshToken().cookieSecure()) {
			log.warn("The refresh token cookie is not marked Secure. Use this only for local development over HTTP.");
		}
		if (!properties.frontendBaseUrl().startsWith("https://") && environment.acceptsProfiles(Profiles.of("prod"))) {
			throw new IllegalStateException("app.security.frontend-base-url must be https:// when the prod profile is active");
		}
	}
}
