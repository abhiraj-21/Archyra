package com.abhiraj.archyra.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes the standard {@code { code, message }} error body from servlet filters and security handlers, which run
 * before (or outside) the MVC layer and so cannot use {@code GlobalExceptionHandler}. Only fixed strings are
 * written: nothing from the request or an exception ever reaches the body.
 */
final class ApiErrorWriter {

	private ApiErrorWriter() {
	}

	/** {@code code} and {@code message} must be constants without quotes or backslashes. */
	static void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}
}
