package com.abhiraj.archyra.security;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Everything security-related that is configurable ({@code app.security.*}). Secrets come from environment
 * variables; this record never prints them ({@code toString} of the secret-bearing parts is redacted).
 *
 * @param jwt                      access token settings
 * @param refreshToken             refresh token and its cookie
 * @param verificationTokenTtl     lifetime of an email verification token
 * @param passwordResetTokenTtl    lifetime of a password reset token
 * @param frontendBaseUrl          base URL of the SPA; the only source of redirect and email-link targets
 * @param cors                     allowed browser origins
 * @param oauth2                   where the SPA is sent after an OAuth2 login attempt
 * @param rateLimit                per-client throttles
 * @param lockout                  temporary account lockout after failed logins
 * @param bootstrapAdmin           optional first administrator
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
		@Valid @NotNull Jwt jwt,
		@Valid @NotNull RefreshToken refreshToken,
		@DefaultValue("24h") @NotNull Duration verificationTokenTtl,
		@DefaultValue("30m") @NotNull Duration passwordResetTokenTtl,
		@NotBlank String frontendBaseUrl,
		@Valid @NotNull Cors cors,
		@Valid @NotNull OAuth2 oauth2,
		@Valid @DefaultValue RateLimit rateLimit,
		@Valid @DefaultValue Lockout lockout,
		@DefaultValue BootstrapAdmin bootstrapAdmin) {

	/** HS256 needs a key of at least 256 bits. */
	public static final int MIN_SECRET_BYTES = 32;

	/**
	 * @param secret         base64 of at least 32 random bytes ({@code openssl rand -base64 32})
	 * @param issuer         the {@code iss} claim, checked on every token
	 * @param accessTokenTtl lifetime of an access token
	 */
	public record Jwt(
			@NotBlank String secret,
			@DefaultValue("archyra") @NotBlank String issuer,
			@DefaultValue("15m") @NotNull Duration accessTokenTtl) {

		public Jwt {
			if (secret != null && !secret.isBlank()) {
				byte[] key;
				try {
					key = Base64.getDecoder().decode(secret.trim());
				}
				catch (IllegalArgumentException ex) {
					throw new IllegalArgumentException("app.security.jwt.secret must be base64 (openssl rand -base64 32)");
				}
				if (key.length < MIN_SECRET_BYTES) {
					throw new IllegalArgumentException(
							"app.security.jwt.secret must decode to at least " + MIN_SECRET_BYTES + " bytes");
				}
			}
		}

		/** The raw HMAC key. */
		public byte[] secretBytes() {
			return Base64.getDecoder().decode(secret.trim());
		}

		@Override
		public String toString() {
			return "Jwt[secret=<redacted>, issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
		}
	}

	/**
	 * @param ttl          refresh token lifetime (also the cookie's Max-Age)
	 * @param cookieName   name of the HttpOnly cookie
	 * @param cookiePath   path the cookie is sent to; must cover the refresh and logout endpoints
	 * @param cookieSecure the {@code Secure} flag; may be false only outside production
	 */
	public record RefreshToken(
			@DefaultValue("7d") @NotNull Duration ttl,
			@DefaultValue("refresh_token") @NotBlank String cookieName,
			@DefaultValue("/api/v1/auth") @NotBlank String cookiePath,
			@DefaultValue("true") boolean cookieSecure) {
	}

	/** @param allowedOrigins exact origins allowed to call the API from a browser; never {@code *} */
	public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {

		public Cors {
			if (allowedOrigins != null && allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
				throw new IllegalArgumentException("app.security.cors.allowed-origins must list exact origins, not wildcards");
			}
		}
	}

	/** Paths are appended to {@code frontend-base-url}; they are never taken from a request. */
	public record OAuth2(
			@DefaultValue("/auth/callback") @NotBlank String successRedirectPath,
			@DefaultValue("/login") @NotBlank String failureRedirectPath) {
	}

	/**
	 * @param loginPerIpPerMinute        {@code POST /auth/login} per client IP
	 * @param sensitivePerIpPerHour      register, forgot-password and resend-verification per client IP
	 * @param createAnalysisPerUserPerHour {@code POST /analyses} per signed-in user (it triggers a git clone)
	 * @param linkIntentPerUserPerHour   starting an account link ({@code .../link-intent}) per signed-in user
	 * @param billingCreatePerUserPerHour creating a Razorpay order or subscription per signed-in user
	 * @param billingVerifyPerUserPerHour verifying a checkout per signed-in user (a wrong signature counts too)
	 * @param billingWebhookPerIpPerMinute the Razorpay webhook per client IP; generous, Razorpay retries
	 */
	public record RateLimit(
			@DefaultValue("10") @Min(1) int loginPerIpPerMinute,
			@DefaultValue("5") @Min(1) int sensitivePerIpPerHour,
			@DefaultValue("10") @Min(1) int createAnalysisPerUserPerHour,
			@DefaultValue("10") @Min(1) int linkIntentPerUserPerHour,
			@DefaultValue("10") @Min(1) int aiChatPerUserPerMinute,
			@DefaultValue("20") @Min(1) int billingCreatePerUserPerHour,
			@DefaultValue("30") @Min(1) int billingVerifyPerUserPerHour,
			@DefaultValue("600") @Min(1) int billingWebhookPerIpPerMinute) {
	}

	public record Lockout(
			@DefaultValue("5") @Min(1) int maxFailedAttempts,
			@DefaultValue("15m") @NotNull Duration lockDuration) {
	}

	/** Both values must be set for an admin to be created at startup, and only if no admin exists yet. */
	public record BootstrapAdmin(String email, String password) {

		public boolean configured() {
			return email != null && !email.isBlank() && password != null && !password.isBlank();
		}

		@Override
		public String toString() {
			return "BootstrapAdmin[email=" + email + ", password=<redacted>]";
		}
	}
}
