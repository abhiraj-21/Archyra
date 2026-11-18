package com.abhiraj.archyra.security;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

/** A complete {@link SecurityProperties} for unit tests that need one. */
public final class TestSecurityProperties {

	private TestSecurityProperties() {
	}

	public static SecurityProperties create() {
		return withBootstrapAdmin(null, null);
	}

	public static SecurityProperties withBootstrapAdmin(String email, String password) {
		return new SecurityProperties(
				new SecurityProperties.Jwt(Base64.getEncoder().encodeToString(new byte[32]), "archyra", Duration.ofMinutes(15)),
				new SecurityProperties.RefreshToken(Duration.ofDays(7), "refresh_token", "/api/v1/auth", true),
				Duration.ofHours(24), Duration.ofMinutes(30), "https://app.example.com/",
				new SecurityProperties.Cors(List.of("https://app.example.com")),
				new SecurityProperties.OAuth2("/auth/callback", "/login"),
				new SecurityProperties.RateLimit(10, 5, 10, 10),
				new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
				new SecurityProperties.BootstrapAdmin(email, password));
	}
}
