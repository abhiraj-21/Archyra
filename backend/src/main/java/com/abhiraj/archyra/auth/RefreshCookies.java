package com.abhiraj.archyra.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.security.SecurityProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * The refresh token cookie: {@code HttpOnly; SameSite=Strict; Path=/api/v1/auth}, {@code Secure} unless
 * configured off for local development. SameSite=Strict assumes the SPA and the API share a registrable domain.
 */
@Component
@RequiredArgsConstructor
public class RefreshCookies {

	private final SecurityProperties properties;

	/** Every issued token lives for the configured TTL, so that is the cookie's Max-Age. */
	public String set(RefreshTokenService.Issued token) {
		return build(token.rawToken(), properties.refreshToken().ttl()).toString();
	}

	/** An expired cookie with the same name and path, which makes the browser delete it. */
	public String clear() {
		return build("", Duration.ZERO).toString();
	}

	/** The raw refresh token the browser sent, or null. */
	String read(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (cookie.getName().equals(properties.refreshToken().cookieName()) && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private ResponseCookie build(String value, Duration maxAge) {
		var config = properties.refreshToken();
		return ResponseCookie.from(config.cookieName(), value)
				.httpOnly(true)
				.secure(config.cookieSecure())
				.sameSite("Strict")
				.path(config.cookiePath())
				.maxAge(maxAge)
				.build();
	}
}
