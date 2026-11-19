package com.abhiraj.archyra.usage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.abhiraj.archyra.user.Provider;

/**
 * The identity keys of the usage ledger: {@code HMAC-SHA256(secret, value)} as lower-case hex (64 characters). A key says
 * "this email" or "this social identity" and cannot be turned back into either without the secret. Raw values are never
 * stored or logged.
 */
final class LedgerKeys {

	private static final String HMAC = "HmacSHA256";

	private final SecretKeySpec key;

	LedgerKeys(String secret) {
		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC);
	}

	String emailKey(String email) {
		return hmac(normalizeEmail(email));
	}

	String identityKey(Provider provider, String providerUserId) {
		return hmac(provider.name().toLowerCase(Locale.ROOT) + ":" + providerUserId);
	}

	/**
	 * Trim and lower-case. For {@code gmail.com} and {@code googlemail.com} (one mailbox) the dots of the local part and
	 * everything from {@code +} go too and the domain becomes {@code gmail.com}; for every other domain only a {@code +tag}
	 * suffix is dropped. So {@code a.b+x@gmail.com}, {@code A.B@googlemail.com} and {@code ab@gmail.com} are one key.
	 * Registration and every other creation path use this one function.
	 */
	static String normalizeEmail(String email) {
		String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
		int at = value.lastIndexOf('@');
		if (at <= 0 || at == value.length() - 1) {
			return value; // not an address; keep it distinct rather than guess
		}
		String local = value.substring(0, at);
		String domain = value.substring(at + 1);
		boolean gmail = domain.equals("gmail.com") || domain.equals("googlemail.com");
		int plus = local.indexOf('+');
		if (plus > 0) {
			local = local.substring(0, plus);
		}
		if (gmail) {
			local = local.replace(".", "");
			domain = "gmail.com";
		}
		return local.isEmpty() ? value : local + "@" + domain;
	}

	private String hmac(String value) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 is not available", ex);
		}
	}
}
