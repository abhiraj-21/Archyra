package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

class TokenHasherTest {

	@Test
	void tokensAre32RandomBytesInUrlSafeBase64WithoutPadding() {
		String token = TokenHasher.newToken();

		assertThat(token).matches("[A-Za-z0-9_-]{43}");
		assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
	}

	@Test
	void tokensDoNotRepeat() {
		var seen = new HashSet<String>();
		for (int i = 0; i < 1000; i++) {
			assertThat(seen.add(TokenHasher.newToken())).isTrue();
		}
	}

	@Test
	void theHashIsAStableSha256Hex() {
		assertThat(TokenHasher.hash("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
				.isEqualTo(TokenHasher.hash("abc"));
		assertThat(TokenHasher.hash("abd")).isNotEqualTo(TokenHasher.hash("abc"));
	}
}
