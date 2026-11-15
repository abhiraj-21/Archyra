package com.abhiraj.archyra.common;

/**
 * Machine-readable error body returned by every failing endpoint.
 */
public record ErrorResponse(String code, String message) {
}
