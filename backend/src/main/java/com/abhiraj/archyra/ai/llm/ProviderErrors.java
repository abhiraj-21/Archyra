package com.abhiraj.archyra.ai.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.abhiraj.archyra.ai.llm.ProviderException.Kind;
import com.google.genai.errors.ApiException;

/**
 * Turns what the HTTP layer and the Gemini SDK throw into a {@link ProviderException}, looking through the wrappers around
 * it. Reads only status codes and the {@code Retry-After} header; a response body is never read into a message.
 */
final class ProviderErrors {

	private ProviderErrors() {
	}

	static ProviderException classify(String provider, Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof ProviderException already) {
				return already;
			}
			if (cause instanceof ApiException api) {
				return new ProviderException(provider, kindOf(api.code()), null, failure); // the Gemini SDK exposes no headers
			}
			if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
					|| cause instanceof InterruptedIOException || cause instanceof TimeoutException) {
				return new ProviderException(provider, Kind.TIMEOUT, null, failure);
			}
			if (cause instanceof IOException) {
				return new ProviderException(provider, Kind.UNAVAILABLE, null, failure);
			}
		}
		return new ProviderException(provider, Kind.OTHER, null, failure);
	}

	static Kind kindOf(int status) {
		if (status == 429) {
			return Kind.RATE_LIMITED;
		}
		if (status == 401 || status == 402 || status == 403) {
			return Kind.BAD_KEY;
		}
		if (status == 404) {
			return Kind.NOT_FOUND;
		}
		if (status == 408) {
			return Kind.TIMEOUT;
		}
		if (status >= 500) {
			return Kind.UNAVAILABLE;
		}
		return Kind.OTHER;
	}

	/** {@code Retry-After} in whole seconds, if the provider sent one (a date is not understood: the default applies). */
	static Duration retryAfter(String header) {
		if (header == null) {
			return null;
		}
		try {
			long seconds = (long) Math.ceil(Double.parseDouble(header.trim()));
			return seconds > 0 ? Duration.ofSeconds(seconds) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}
}
