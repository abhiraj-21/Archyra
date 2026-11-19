package com.abhiraj.archyra.usage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.abhiraj.archyra.user.Provider;

class LedgerKeysTest {

	private static final String SECRET = "archyra-test-only-usage-ledger-secret-0123456789";

	private final LedgerKeys keys = new LedgerKeys(SECRET);

	@ParameterizedTest
	@CsvSource({
			"a.b+x@gmail.com, ab@gmail.com",
			"  A.B+Promo@Gmail.com , ab@gmail.com",
			"a.b@googlemail.com, ab@gmail.com",
			"ab+1@googlemail.com, ab@gmail.com",
			"a.b+tag@example.com, a.b@example.com",
			"Ada.Lovelace@Example.COM, ada.lovelace@example.com",
			"a+1@x.com, a@x.com",
			"a+b+c@x.com, a@x.com" })
	void normalisesTheWayTheSpecSays(String input, String expected) {
		assertThat(LedgerKeys.normalizeEmail(input)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({ "a.b@example.com, ab@example.com", "ab@example.com, abc@example.com", "ab@gmail.com, ab@example.com" })
	void doesNotMergeAddressesThatAreDifferentMailboxes(String left, String right) {
		assertThat(keys.emailKey(left)).isNotEqualTo(keys.emailKey(right));
	}

	@Test
	void dotsOnlyCountForGmail() {
		assertThat(keys.emailKey("a.b@x.com")).isNotEqualTo(keys.emailKey("ab@x.com"));
		assertThat(keys.emailKey("a.b@gmail.com")).isEqualTo(keys.emailKey("ab@gmail.com"));
	}

	@Test
	void aLeadingPlusIsNotATagAndAnythingOddIsKeptDistinct() {
		assertThat(LedgerKeys.normalizeEmail("+tag@x.com")).isEqualTo("+tag@x.com");
		assertThat(LedgerKeys.normalizeEmail("not-an-email")).isEqualTo("not-an-email");
		assertThat(LedgerKeys.normalizeEmail(null)).isEmpty();
	}

	@Test
	void aKeyIs64HexCharactersAndDependsOnTheSecret() {
		String key = keys.emailKey("ada@example.com");

		assertThat(key).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("ada");
		assertThat(new LedgerKeys(SECRET + "-other").emailKey("ada@example.com")).isNotEqualTo(key);
		assertThat(new LedgerKeys(SECRET).emailKey("ada@example.com")).isEqualTo(key);
	}

	@Test
	void identityKeysAreSeparateForEachProviderAndAccount() {
		assertThat(keys.identityKey(Provider.GOOGLE, "123")).isNotEqualTo(keys.identityKey(Provider.GITHUB, "123"))
				.isNotEqualTo(keys.identityKey(Provider.GOOGLE, "124")).isEqualTo(keys.identityKey(Provider.GOOGLE, "123"));
	}
}
