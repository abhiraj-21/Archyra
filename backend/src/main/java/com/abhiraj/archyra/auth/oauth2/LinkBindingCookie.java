package com.abhiraj.archyra.auth.oauth2;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.security.SecurityProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * The browser-binding cookie of a link intent: {@code HttpOnly; SameSite=Strict; Path=/oauth2/authorization;
 * Max-Age=300} ({@code Secure} unless configured off for local development). It is only ever sent to the
 * authorization start URLs, and it is what stops a crafted {@code link_intent} URL from working in another browser.
 */
@Component
@RequiredArgsConstructor
public class LinkBindingCookie {

	static final String NAME = "link_binding";
	static final String PATH = "/oauth2/authorization";

	private final SecurityProperties properties;

	public String set(String bindingSecret, Duration ttl) {
		return build(bindingSecret, ttl).toString();
	}

	/** An expired cookie with the same name and path, which makes the browser delete it. */
	String clear() {
		return build("", Duration.ZERO).toString();
	}

	String read(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if (NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
					return cookie.getValue();
				}
			}
		}
		return null;
	}

	private ResponseCookie build(String value, Duration maxAge) {
		return ResponseCookie.from(NAME, value).httpOnly(true).secure(properties.refreshToken().cookieSecure())
				.sameSite("Strict").path(PATH).maxAge(maxAge).build();
	}
}
