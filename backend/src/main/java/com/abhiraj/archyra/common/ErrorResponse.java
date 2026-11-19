package com.abhiraj.archyra.common;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Machine-readable error body returned by every failing endpoint. {@code resetsAt} is only set by
 * {@code USAGE_LIMIT_EXCEEDED} of a weekly allowance and is left out of every other body.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Instant resetsAt) {

	public ErrorResponse(String code, String message) {
		this(code, message, null);
	}
}
