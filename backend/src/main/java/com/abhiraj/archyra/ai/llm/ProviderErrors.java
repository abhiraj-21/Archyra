package com.abhiraj.archyra.ai.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.abhiraj.archyra.ai.llm.ProviderException.Kind;
import com.google.genai.errors.ApiException;
import com.openai.errors.OpenAIServiceException;

/**
 * Turns whatever the SDKs throw into a {@link ProviderException}, looking through the wrappers Spring AI adds. Reads only
 * status codes and the {@code Retry-After} header; a response body is never read into a message.
 */
final class ProviderErrors {

	private ProviderErrors() {
	}

	static ProviderException classify(ProviderId provider, Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof ProviderException already) {
				return already;
			}
			if (cause instanceof ApiException api) {
				return new ProviderException(provider, kindOf(api.code()), null, failure); // the Gemini SDK exposes no headers
			}
			if (cause instanceof OpenAIServiceException api) {
				Kind kind = kindOf(api.statusCode());
				Duration retryAfter = kind == Kind.RATE_LIMITED ? retryAfter(api) : null;
				return new ProviderException(provider, kind, retryAfter, failure);
			}
			if (cause instanceof SocketTimeoutException || cause instanceof InterruptedIOException
					|| cause instanceof TimeoutException) {
				return new ProviderException(provider, Kind.TIMEOUT, null, failure);
			}
			if (cause instanceof IOException) {
				return new ProviderException(provider, Kind.UNAVAILABLE, null, failure);
			}
		}
		return new ProviderException(provider, Kind.OTHER, null, failure);
	}

	private static Kind kindOf(int status) {
		if (status == 429) {
			return Kind.RATE_LIMITED;
		}
		if (status == 401 || status == 403) {
			return Kind.BAD_KEY;
		}
		if (status >= 500) {
			return Kind.UNAVAILABLE;
		}
		return Kind.OTHER;
	}

	/** {@code Retry-After} in whole seconds, if the provider sent one. */
	private static Duration retryAfter(OpenAIServiceException failure) {
		try {
			for (String value : failure.headers().values("retry-after")) {
				long seconds = (long) Math.ceil(Double.parseDouble(value.trim()));
				if (seconds > 0) {
					return Duration.ofSeconds(seconds);
				}
			}
		}
		catch (RuntimeException ignored) {
			// no usable header: the default cool-down applies
		}
		return null;
	}
}
