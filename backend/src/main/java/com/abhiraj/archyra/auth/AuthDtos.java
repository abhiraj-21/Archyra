package com.abhiraj.archyra.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies of {@code /api/v1/auth}. */
final class AuthDtos {

	private AuthDtos() {
	}

	/** Validation messages never include what the client sent. */
	record RegisterRequest(
			@NotBlank(message = "email must not be blank") @Email(message = "email must be a valid address")
			@Size(max = 254, message = "email must be at most 254 characters") String email,
			@NotBlank(message = "password must not be blank")
			@Size(max = PasswordPolicy.MAX_LENGTH, message = "password must be at most 128 characters") String password,
			@NotBlank(message = "name must not be blank")
			@Size(max = 100, message = "name must be at most 100 characters") String name) {

		/** Surrounding spaces are not part of an email address. */
		public RegisterRequest {
			email = email == null ? null : email.trim();
		}

		@Override
		public String toString() {
			return "RegisterRequest[email=" + email + ", password=<redacted>, name=" + name + "]";
		}
	}

	record LoginRequest(
			@NotBlank(message = "email must not be blank")
			@Size(max = 254, message = "email must be at most 254 characters") String email,
			@NotBlank(message = "password must not be blank")
			@Size(max = PasswordPolicy.MAX_LENGTH, message = "password must be at most 128 characters") String password) {

		public LoginRequest {
			email = email == null ? null : email.trim();
		}

		@Override
		public String toString() {
			return "LoginRequest[email=" + email + ", password=<redacted>]";
		}
	}

	record MessageResponse(String message) {
	}

	record VerifyEmailRequest(
			@NotBlank(message = "token must not be blank")
			@Size(max = 200, message = "token must be at most 200 characters") String token) {

		@Override
		public String toString() {
			return "VerifyEmailRequest[token=<redacted>]";
		}
	}

	/** Used by resend-verification and forgot-password. */
	record EmailRequest(
			@NotBlank(message = "email must not be blank")
			@Size(max = 254, message = "email must be at most 254 characters") String email) {

		public EmailRequest {
			email = email == null ? null : email.trim();
		}
	}

	record ResetPasswordRequest(
			@NotBlank(message = "token must not be blank")
			@Size(max = 200, message = "token must be at most 200 characters") String token,
			@NotBlank(message = "newPassword must not be blank")
			@Size(max = PasswordPolicy.MAX_LENGTH, message = "newPassword must be at most 128 characters") String newPassword) {

		@Override
		public String toString() {
			return "ResetPasswordRequest[token=<redacted>, newPassword=<redacted>]";
		}
	}

	record ChangePasswordRequest(
			@NotBlank(message = "currentPassword must not be blank")
			@Size(max = PasswordPolicy.MAX_LENGTH, message = "currentPassword must be at most 128 characters") String currentPassword,
			@NotBlank(message = "newPassword must not be blank")
			@Size(max = PasswordPolicy.MAX_LENGTH, message = "newPassword must be at most 128 characters") String newPassword) {

		@Override
		public String toString() {
			return "ChangePasswordRequest[currentPassword=<redacted>, newPassword=<redacted>]";
		}
	}

	/** What login and refresh return. The refresh token travels only in its cookie. */
	record AuthResponse(String accessToken, String tokenType, long expiresIn) {

		@Override
		public String toString() {
			return "AuthResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
		}
	}

	record CsrfResponse(String headerName, String token) {
	}
}
