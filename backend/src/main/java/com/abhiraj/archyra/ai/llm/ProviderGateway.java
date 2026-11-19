package com.abhiraj.archyra.ai.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * The only way to a chat model. Tries the configured providers in order (Gemini, then Groq) within
 * {@code archyra.ai.timeout.total}, and goes on to the next one on a 429, a 5xx, a timeout or connection error, an empty
 * or blocked answer and a bad key (logged loudly). The same provider is never tried twice for one question. A 429 starts
 * that provider's cool-down, and a provider that is cooling down is skipped.
 *
 * <p>The prompt is built <em>per provider</em> (a fallback has a smaller context budget), so the caller passes a function
 * that prepares the request for the provider about to be called. Nothing here logs a prompt, an answer or a key.
 */
@Slf4j
@Component
public class ProviderGateway implements DisposableBean {

	/** What the caller prepared for one provider: the request, and whatever it needs back afterwards (the numbered context). */
	public record Prepared<T>(LlmRequest request, T attachment) {
	}

	/** The answer, the provider that gave it, and what was prepared for that provider. */
	public record Result<T>(LlmAnswer answer, T attachment) {
	}

	private final List<ChatProvider> providers;
	private final ProviderCooldowns cooldowns;
	private final AiProperties properties;
	private final Clock clock;
	private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

	ProviderGateway(List<ChatProvider> providers, ProviderCooldowns cooldowns, AiProperties properties, Clock clock) {
		this.providers = providers.stream().sorted(Comparator.comparing(ChatProvider::id)).toList();
		this.cooldowns = cooldowns;
		this.properties = properties;
		this.clock = clock;
	}

	/** Builds the provider clients once the application is up, so the first question is not slowed by it. */
	@EventListener(ApplicationReadyEvent.class)
	void warmUp() {
		for (ChatProvider provider : providers) {
			try {
				provider.warmUp();
			}
			catch (RuntimeException ex) {
				log.warn("Could not prepare provider {} ({}); it will be built on first use", provider.id(), ex.getClass().getSimpleName());
			}
		}
	}

	/** Whether any provider is configured and not cooling down: the pre-flight check, before anything is charged. */
	public boolean anyUsable() {
		return providers.stream().anyMatch(this::usable);
	}

	private boolean usable(ChatProvider provider) {
		return provider.configured() && !cooldowns.isCoolingDown(provider.id());
	}

	/**
	 * @throws AllProvidersFailedException if no provider answered
	 */
	public <T> Result<T> ask(Function<ChatProvider, Prepared<T>> prepare) {
		Instant deadline = clock.instant().plus(properties.timeout().total());
		RuntimeException last = null;
		for (ChatProvider provider : providers) {
			if (!usable(provider)) {
				continue;
			}
			Duration remaining = Duration.between(clock.instant(), deadline);
			if (remaining.isNegative() || remaining.isZero()) {
				break;
			}
			Prepared<T> prepared = prepare.apply(provider);
			long started = System.nanoTime();
			try {
				LlmAnswer answer = call(provider, prepared.request(), shorter(remaining, attemptLimit()));
				log.info("Provider {} answered in {} ms", provider.id(), millisSince(started));
				return new Result<>(answer, prepared.attachment());
			}
			catch (ProviderException ex) {
				last = ex;
				onFailure(ex, millisSince(started));
			}
		}
		throw new AllProvidersFailedException(last);
	}

	/**
	 * What one provider may take: connect plus read timeout. The SDKs' own timeouts are set too, but the gateway does not
	 * depend on them (the Gemini SDK, for one, did not cut a slow call), so a stuck provider cannot eat the whole budget.
	 */
	private Duration attemptLimit() {
		return properties.timeout().connect().plus(properties.timeout().read());
	}

	private static Duration shorter(Duration a, Duration b) {
		return a.compareTo(b) <= 0 ? a : b;
	}

	private LlmAnswer call(ChatProvider provider, LlmRequest request, Duration remaining) {
		Future<LlmAnswer> future = calls.submit(() -> provider.chat(request));
		try {
			return future.get(remaining.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw new ProviderException(provider.id(), ProviderException.Kind.TIMEOUT, null, ex);
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new ProviderException(provider.id(), ProviderException.Kind.OTHER, null, ex);
		}
		catch (ExecutionException ex) {
			throw ProviderErrors.classify(provider.id(), ex.getCause() == null ? ex : ex.getCause());
		}
	}

	private void onFailure(ProviderException failure, long millis) {
		switch (failure.kind()) {
			case RATE_LIMITED -> {
				cooldowns.start(failure.provider(), failure.retryAfter());
				log.warn("Provider {} is rate limited after {} ms; cooling down", failure.provider(), millis);
			}
			case BAD_KEY -> log.error("Provider {} refused its API key (401 or 403): check the key", failure.provider());
			default -> log.warn("Provider {} failed ({}) after {} ms; trying the next one", failure.provider(), failure.kind(), millis);
		}
	}

	private static long millisSince(long startedNanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
	}

	@Override
	public void destroy() {
		calls.shutdownNow();
	}
}
