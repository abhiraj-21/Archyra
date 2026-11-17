package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import com.abhiraj.archyra.common.InvalidRequestException;

class PasswordPolicyTest {

	@Test
	void acceptsALongEnoughUncommonPassword() {
		assertThatCode(() -> PasswordPolicy.check("correct horse battery", "a@example.com")).doesNotThrowAnyException();
	}

	@Test
	void enforcesTheLengthBounds() {
		assertThatThrownBy(() -> PasswordPolicy.check("short", "a@example.com")).isInstanceOf(InvalidRequestException.class);
		assertThatCode(() -> PasswordPolicy.check("a".repeat(72), "a@example.com")).doesNotThrowAnyException();
		assertThatThrownBy(() -> PasswordPolicy.check("a".repeat(73), "a@example.com"))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("72 bytes");
		assertThatThrownBy(() -> PasswordPolicy.check("a".repeat(129), "a@example.com"))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void rejectsThePasswordThatIsTheEmail() {
		assertThatThrownBy(() -> PasswordPolicy.check("Ada@Example.com", " ada@example.com "))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void rejectsCommonPasswordsInAnyCase() {
		assertThatThrownBy(() -> PasswordPolicy.check("PassWord123", "a@example.com"))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("too common");
	}

	@Test
	void messagesNeverRepeatThePasswordOrEmail() {
		assertThatThrownBy(() -> PasswordPolicy.check("ada@example.com", "ada@example.com"))
				.satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("ada@example.com"));
	}

	@Test
	void theEncoderHandlesTheLongestAllowedPassword() {
		var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		String longest = "é".repeat(PasswordPolicy.MAX_BYTES / 2); // exactly 72 bytes in UTF-8
		assertThatCode(() -> PasswordPolicy.check(longest, "a@example.com")).doesNotThrowAnyException();
		assertThat(PasswordPolicy.exceedsHashLimit(longest + "x")).isTrue();

		String hash = encoder.encode(longest);

		assertThat(hash).startsWith("{bcrypt}");
		assertThat(encoder.matches(longest, hash)).isTrue();
		assertThat(encoder.matches("é".repeat(PasswordPolicy.MAX_BYTES / 2 - 1), hash)).isFalse();
	}
}
