package com.abhiraj.archyra.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import com.abhiraj.archyra.common.InvalidRequestException;

/**
 * Length and a short list of the most common passwords; no composition rules (they push people to predictable
 * patterns). Messages never repeat the password or the email.
 */
final class PasswordPolicy {

	static final int MIN_LENGTH = 8;
	/** Upper bound on what is accepted at all, so a huge body is never hashed. */
	static final int MAX_LENGTH = 128;
	/** bcrypt, the delegating encoder's default, refuses more than 72 bytes (it would otherwise truncate silently). */
	static final int MAX_BYTES = 72;

	private static final Set<String> COMMON = Set.of(
			"password", "password1", "password12", "password123", "passw0rd", "p@ssw0rd", "12345678", "123456789",
			"1234567890", "11111111", "00000000", "12341234", "qwertyui", "qwertyuiop", "qwerty123", "qwerty1234",
			"abc12345", "abcd1234", "iloveyou", "letmein1", "welcome1", "welcome123", "admin123", "administrator",
			"football", "baseball", "superman", "trustno1", "sunshine", "princess", "whatever", "changeme",
			"monkey123", "dragon123", "master123", "login123", "archyra123");

	private PasswordPolicy() {
	}

	/** True if the encoder cannot hash it, so it can never be anyone's password. */
	static boolean exceedsHashLimit(String password) {
		return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
	}

	/** @throws InvalidRequestException if the password is not acceptable */
	static void check(String password, String email) {
		if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
			throw new InvalidRequestException(
					"Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
		}
		if (exceedsHashLimit(password)) {
			throw new InvalidRequestException("Password must be at most " + MAX_BYTES + " bytes (about "
					+ MAX_BYTES + " characters; fewer if it contains non-ASCII characters)");
		}
		String lower = password.toLowerCase(Locale.ROOT);
		if (email != null && lower.equals(email.trim().toLowerCase(Locale.ROOT))) {
			throw new InvalidRequestException("Password must not be the same as your email address");
		}
		if (COMMON.contains(lower)) {
			throw new InvalidRequestException("That password is too common. Choose a different one");
		}
	}
}
