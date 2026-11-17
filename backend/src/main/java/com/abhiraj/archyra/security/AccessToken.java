package com.abhiraj.archyra.security;

/**
 * An issued access token as the login endpoints return it.
 *
 * @param value     the compact JWT, sent by clients as {@code Authorization: Bearer <value>}
 * @param expiresIn lifetime in seconds
 */
public record AccessToken(String value, long expiresIn) {

	public static final String TOKEN_TYPE = "Bearer";

	@Override
	public String toString() {
		return "AccessToken[value=<redacted>, expiresIn=" + expiresIn + "]";
	}
}
