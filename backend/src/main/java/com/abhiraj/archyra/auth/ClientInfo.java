package com.abhiraj.archyra.auth;

/** Where a request came from, kept with a refresh token and in audit lines. Both values may be null. */
public record ClientInfo(String ip, String userAgent) {
}
