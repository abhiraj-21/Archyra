package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityPropertiesTest {

	private static String base64Of(int bytes) {
		return Base64.getEncoder().encodeToString("x".repeat(bytes).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void aSecretOf32BytesIsAccepted() {
		var jwt = new SecurityProperties.Jwt(base64Of(32), "archyra", Duration.ofMinutes(15));

		assertThat(jwt.secretBytes()).hasSize(32);
	}

	@Test
	void aSecretUnder32BytesFailsStartup() {
		assertThatThrownBy(() -> new SecurityProperties.Jwt(base64Of(31), "archyra", Duration.ofMinutes(15)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 32 bytes");
	}

	@Test
	void aSecretThatIsNotBase64FailsStartup() {
		assertThatThrownBy(() -> new SecurityProperties.Jwt("not base64 !!", "archyra", Duration.ofMinutes(15)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("base64");
	}

	@Test
	void theSecretIsNeverPrinted() {
		var jwt = new SecurityProperties.Jwt(base64Of(32), "archyra", Duration.ofMinutes(15));

		assertThat(jwt.toString()).doesNotContain(base64Of(32)).contains("<redacted>");
	}

	@Test
	void wildcardCorsOriginsAreRefused() {
		assertThatThrownBy(() -> new SecurityProperties.Cors(List.of("*")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SecurityProperties.Cors(List.of("https://*.example.com")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anInsecureRefreshCookieIsRefusedInProduction() {
		var props = JwtServiceTest.properties(base64Of(32), "archyra");
		var insecure = new SecurityProperties(props.jwt(),
				new SecurityProperties.RefreshToken(Duration.ofDays(7), "refresh_token", "/api/v1/auth", false),
				props.verificationTokenTtl(), props.passwordResetTokenTtl(), props.frontendBaseUrl(), props.cors(),
				props.oauth2(), props.rateLimit(), props.lockout(), props.bootstrapAdmin());

		assertThatThrownBy(() -> new SecurityStartupChecks(insecure, withProfile(new MockEnvironment(), "prod")))
				.isInstanceOf(IllegalStateException.class);
		new SecurityStartupChecks(insecure, withProfile(new MockEnvironment(), "dev")); // fine outside prod
	}

	@Test
	void anHttpFrontendIsRefusedInProduction() {
		var props = JwtServiceTest.properties(base64Of(32), "archyra"); // frontend is http://localhost

		assertThatThrownBy(() -> new SecurityStartupChecks(props, withProfile(new MockEnvironment(), "prod")))
				.isInstanceOf(IllegalStateException.class);
	}

	private static MockEnvironment withProfile(MockEnvironment env, String profile) {
		env.setActiveProfiles(profile);
		return env;
	}
}
