package com.abhiraj.archyra.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 403 for an authenticated caller who lacks the required role. */
@Component
class RestAccessDeniedHandler implements AccessDeniedHandler {

	static final String CODE = "ACCESS_DENIED";

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		if (accessDeniedException instanceof CsrfException) {
			ApiErrorWriter.write(response, HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID",
					"The CSRF token is missing or invalid");
			return;
		}
		ApiErrorWriter.write(response, HttpStatus.FORBIDDEN, CODE, "You do not have permission to do this");
	}
}
