package com.abhiraj.archyra.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 401 for a request with no token, or a bad one (wrong signature, expired, wrong issuer, ...). The response
 * keeps the {@code WWW-Authenticate: Bearer} header, adds {@code error="invalid_token"} when a token was
 * presented, and never says why it was rejected.
 */
@Component
class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	static final String CODE = "AUTHENTICATION_REQUIRED";

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		boolean badToken = authException instanceof OAuth2AuthenticationException;
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
		ApiErrorWriter.write(response, HttpStatus.UNAUTHORIZED, CODE,
				badToken ? "The access token is invalid or has expired" : "Authentication is required");
	}
}
