package com.abhiraj.archyra.ai.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import com.abhiraj.archyra.ai.llm.ProviderRegistry.Slot;

import lombok.extern.slf4j.Slf4j;

/**
 * The only way to a chat model. Tries the providers of the chain in their configured order within
 * {@code archyra.ai.timeout.total}, calling at most {@code providers.max-attempts} of them. It goes on to the next one on a
 * 429, a 5xx, a timeout or connection error, an empty or blocked answer, a bad key or an unknown model (the last two logged
 * loudly). The same provider is never tried twice for one question. A provider that is cooling down, or whose local limits
 * (requests per minute, calls in flight, requests per day or month) are used up, is skipped without counting as an attempt.
 *
 * <p>Each attempt gets an equal share of the time left among the providers that could still be tried, never less than
 * {@code min-attempt-timeout} and never more than connect + read, so one stuck provider cannot eat the whole budget.
 *
 * <p>The prompt is built <em>per provider</em> (each has its own context budget), so the caller passes a function that
 * prepares the request for the provider about to be called. Nothing here logs a prompt, an answer or a key.
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

	private final ProviderRegistry registry;
	private final ProviderCooldowns cooldowns;
	private final AiProperties properties;
	private final Clock clock;
	private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

	ProviderGateway(ProviderRegistry registry, ProviderCooldowns cooldowns, AiProperties properties, Clock clock) {
		this.registry = registry;
		this.cooldowns = cooldowns;
		this.properties = properties;
		this.clock = clock;
	}

	/** Builds the provider clients once the application is up, so the first question is not slowed by it. */
	@EventListener(ApplicationReadyEvent.class)
	void warmUp() {
		for (Slot slot : registry.chain()) {
			try {
				slot.provider().warmUp();
			}
			catch (RuntimeException ex) {
				log.warn("Could not prepare provider {} ({}); it will be built on first use", slot.settings().id(), ex.getClass().getSimpleName());
			}
		}
	}

	/** Whether any provider could be called now: in the chain, not cooling down, within its local limits. The pre-flight check, before anything is charged. */
	public boolean anyUsable() {
		return registry.chain().stream().anyMatch(this::usable);
	}

	private boolean usable(Slot slot) {
		return !cooldowns.isCoolingDown(slot.settings().id()) && slot.guard().wouldRefuse() == null;
	}

	/**
	 * @throws AllProvidersFailedException if no provider answered
	 */
	public <T> Result<T> ask(Function<ChatProvider, Prepared<T>> prepare) {
		Instant deadline = clock.instant().plus(properties.timeout().total());
		List<Slot> chain = registry.chain();
		int maxAttempts = properties.providers().maxAttempts();
		int attempts = 0;
		RuntimeException last = null;
		for (int i = 0; i < chain.size() && attempts < maxAttempts; i++) {
			Slot slot = chain.get(i);
			String id = slot.settings().id();
			if (cooldowns.isCoolingDown(id)) {
				continue;
			}
			Duration remaining = Duration.between(clock.instant(), deadline);
			if (remaining.isNegative() || remaining.isZero()) {
				break;
			}
			ProviderGuard.Acquired acquired = slot.guard().tryAcquire();
			if (!acquired.granted()) {
				log.info("Provider {} skipped: local limit reached ({})", id, acquired.refusal());
				continue;
			}
			attempts++;
			Duration limit = attemptLimit(remaining, shareOf(chain, i, maxAttempts - attempts + 1));
			long started = System.nanoTime();
			Prepared<T> prepared;
			try {
				prepared = prepare.apply(slot.provider());
			}
			catch (RuntimeException ex) {
				acquired.permit().release();
				throw ex;
			}
			try {
				LlmAnswer answer = call(slot.provider(), prepared.request(), limit, acquired.permit());
				cooldowns.succeeded(id);
				log.info("Provider {} answered in {} ms", id, millisSince(started));
				return new Result<>(answer, prepared.attachment());
			}
			catch (ProviderException ex) {
				last = ex;
				onFailure(ex, millisSince(started));
			}
		}
		throw new AllProvidersFailedException(last);
	}

	/** How many providers share the time left: this one and the ones after it that could still be called, within the attempts left. */
	private int shareOf(List<Slot> chain, int index, int attemptsLeft) {
		int candidates = 1;
		for (int i = index + 1; i < chain.size(); i++) {
			if (usable(chain.get(i))) {
				candidates++;
			}
		}
		return Math.max(1, Math.min(candidates, attemptsLeft));
	}

	/**
	 * What one provider may take: its share of the time left, at least {@code min-attempt-timeout} and at most connect plus
	 * read, and never more than what is left. The HTTP timeouts are set too, but the gateway does not depend on them (a slow
	 * provider must not be able to eat the whole budget).
	 */
	private Duration attemptLimit(Duration remaining, int share) {
		Duration cap = properties.timeout().connect().plus(properties.timeout().read());
		Duration limit = remaining.dividedBy(share);
		Duration floor = properties.providers().minAttemptTimeout();
		if (limit.compareTo(floor) < 0) {
			limit = floor;
		}
		if (limit.compareTo(cap) > 0) {
			limit = cap;
		}
		return limit.compareTo(remaining) > 0 ? remaining : limit;
	}

	/** The permit is released when the call really ends, so a call the gateway gave up on still counts as in flight until it stops. */
	private LlmAnswer call(ChatProvider provider, LlmRequest request, Duration limit, ProviderGuard.Permit permit) {
		Future<LlmAnswer> future;
		try {
			future = calls.submit(() -> {
				try {
					return provider.chat(request);
				}
				finally {
					permit.release();
				}
			});
		}
		catch (RuntimeException ex) {
			permit.release();
			throw ex;
		}
		try {
			return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
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
		String id = failure.provider();
		switch (failure.kind()) {
			case RATE_LIMITED -> {
				Duration wait = cooldowns.rateLimited(id, failure.retryAfter());
				log.warn("Provider {} is rate limited after {} ms; cooling down for {} s", id, millis, wait.toSeconds());
			}
			case BAD_KEY -> {
				Duration wait = cooldowns.misconfigured(id);
				log.error("Provider {} refused its API key or account (401, 402 or 403): check the key. Not asked again for {} min", id, wait.toMinutes());
			}
			case NOT_FOUND -> {
				Duration wait = cooldowns.misconfigured(id);
				log.error("Provider {} answered 404: check its base URL and model id. Not asked again for {} min", id, wait.toMinutes());
			}
			default -> log.warn("Provider {} failed ({}) after {} ms; trying the next one", id, failure.kind(), millis);
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
