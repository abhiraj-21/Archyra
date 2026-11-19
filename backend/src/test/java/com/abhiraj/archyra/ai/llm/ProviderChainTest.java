package com.abhiraj.archyra.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ai.FakeProviders;

import ch.qos.logback.classic.Level;

/**
 * The provider chain as the application runs it, without a Spring context: the real registry, guards, cool-downs and
 * gateway against WireMock servers that speak the real wire formats. Timing is driven by a movable clock and latches, never
 * sleeps.
 */
class ProviderChainTest {

	private ProviderKit.Chain chain;

	@BeforeEach
	void resetServers() {
		FakeProviders.resetAll();
	}

	@AfterEach
	void close() {
		if (chain != null) {
			chain.close();
		}
	}

	/** gemini, groq, openrouter, zai, each in front of its own fake server. */
	private ProviderKit four() {
		return ProviderKit.create().order("gemini", "groq", "openrouter", "zai").gemini(FakeProviders.GEMINI)
				.openAi("groq", FakeProviders.GROQ).openAi("openrouter", FakeProviders.OPENROUTER)
				.setting("openrouter", "model", "some/model:free").openAi("zai", FakeProviders.ZAI);
	}

	/** groq then openrouter. */
	private ProviderKit two() {
		return ProviderKit.create().order("groq", "openrouter").openAi("groq", FakeProviders.GROQ).openAi("openrouter", FakeProviders.OPENROUTER)
				.setting("openrouter", "model", "some/model:free");
	}

	// ---- order, fallback, attempts, timeouts ---------------------------------------------------

	@Test
	void theFirstHealthyProviderAnswersAndTheRestAreNeverCalled() {
		chain = four().build();
		FakeProviders.GEMINI.answerWith("from gemini");

		assertThat(chain.ask()).isEqualTo(new LlmAnswer("from gemini", "gemini"));

		assertThat(FakeProviders.GEMINI.requestCount()).isEqualTo(1);
		assertThat(FakeProviders.GROQ.requestCount() + FakeProviders.OPENROUTER.requestCount() + FakeProviders.ZAI.requestCount()).isZero();
	}

	@Test
	void aQuestionFallsThroughThreeFailingProvidersToTheFourthInTheSameRequest() {
		chain = four().build();
		FakeProviders.GEMINI.failWith(500);
		FakeProviders.GROQ.rateLimitedFor(1);
		FakeProviders.OPENROUTER.failWith(503);
		FakeProviders.ZAI.answerWith("from zai");

		LlmAnswer answer = chain.ask();

		assertThat(answer).isEqualTo(new LlmAnswer("from zai", "zai"));
		assertThat(List.of(FakeProviders.GEMINI.requestCount(), FakeProviders.GROQ.requestCount(), FakeProviders.OPENROUTER.requestCount(),
				FakeProviders.ZAI.requestCount())).containsExactly(1, 1, 1, 1); // each one once, never twice
	}

	@Test
	void theOrderOfTheConfigurationIsTheOrderOfTheChain() {
		chain = four().order("zai", "openrouter", "groq", "gemini").build();
		FakeProviders.ZAI.failWith(500);
		FakeProviders.OPENROUTER.answerWith("second");

		assertThat(chain.ask().provider()).isEqualTo("openrouter");
		assertThat(FakeProviders.GROQ.requestCount() + FakeProviders.GEMINI.requestCount()).isZero();
	}

	@Test
	void maxAttemptsBoundsHowManyProvidersOneQuestionCalls() {
		chain = four().set("archyra.ai.providers.max-attempts", "2").build();
		FakeProviders.GEMINI.failWith(500);
		FakeProviders.GROQ.failWith(500);
		FakeProviders.OPENROUTER.answerWith("never reached");

		assertThatThrownBy(chain::ask).isInstanceOf(AllProvidersFailedException.class);

		assertThat(FakeProviders.GEMINI.requestCount()).isEqualTo(1);
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(1);
		assertThat(FakeProviders.OPENROUTER.requestCount() + FakeProviders.ZAI.requestCount()).isZero();
	}

	@Test
	void aLocalRefusalIsNotAnAttempt() {
		chain = two().openAi("zai", FakeProviders.ZAI).order("groq", "openrouter", "zai").set("archyra.ai.providers.max-attempts", "2")
				.setting("groq", "requests-per-minute", "1").build();
		chain.ask(); // groq answers and uses up its one request this minute
		FakeProviders.OPENROUTER.failWith(500);
		FakeProviders.ZAI.answerWith("from zai");

		// groq is refused locally (no attempt), openrouter fails (attempt 1), zai answers (attempt 2)
		assertThat(chain.ask().provider()).isEqualTo("zai");
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(1);
	}

	@Test
	void anAttemptGetsAShareOfTheTimeLeftSoAStuckProviderCannotEatTheWholeBudget() {
		chain = two().set("archyra.ai.timeout.read", "30s").set("archyra.ai.timeout.total", "4s").build();
		FakeProviders.GROQ.delayBy(20_000);
		FakeProviders.OPENROUTER.answerWith("on time");
		long started = System.nanoTime();

		LlmAnswer answer = chain.ask();

		// without the share, groq would have waited the whole 4 s and openrouter would never have been tried
		assertThat(answer.provider()).isEqualTo("openrouter");
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(1);
	}

	@Test
	void theTotalTimeoutBoundsTheWholeQuestion() {
		chain = two().set("archyra.ai.timeout.read", "30s").set("archyra.ai.timeout.total", "3s").build();
		FakeProviders.GROQ.delayBy(20_000);
		FakeProviders.OPENROUTER.delayBy(20_000);
		long started = System.nanoTime();

		assertThatThrownBy(chain::ask).isInstanceOf(AllProvidersFailedException.class);

		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
	}

	@Test
	void anEmptyCompletionIsAFailureThatFallsThrough() {
		chain = two().build();
		FakeProviders.GROQ.answerWith("   ");
		FakeProviders.OPENROUTER.answerWith("<think>only thoughts</think>Real answer [1].");

		LlmAnswer answer = chain.ask();

		assertThat(answer).isEqualTo(new LlmAnswer("Real answer [1].", "openrouter")); // thoughts removed, citation text intact
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(1);
	}

	// ---- keys ----------------------------------------------------------------------------------

	@Test
	void aProviderWithoutAKeyIsNotInTheChainAndNeverCalled() {
		chain = two().setting("groq", "api-key", "").build();
		FakeProviders.OPENROUTER.answerWith("only openrouter");

		assertThat(chain.registry().chain()).extracting(slot -> slot.settings().id()).containsExactly("openrouter");
		assertThat(chain.ask().provider()).isEqualTo("openrouter");
		assertThat(FakeProviders.GROQ.requestCount()).isZero();
	}

	@Test
	void withoutAnyKeyNothingIsUsableAndNothingIsCalled() {
		chain = two().setting("groq", "api-key", "").setting("openrouter", "api-key", "").build();

		assertThat(chain.gateway().anyUsable()).isFalse();
		assertThatThrownBy(chain::ask).isInstanceOf(AllProvidersFailedException.class);
		assertThat(FakeProviders.GROQ.requestCount() + FakeProviders.OPENROUTER.requestCount()).isZero();
	}

	@Test
	void everyProviderReceivesOnlyItsOwnKeyAndNoKeyReachesALogOrAnError() {
		try (LogCapture logs = new LogCapture()) {
			chain = four().build();
			FakeProviders.GEMINI.failWith(500);
			FakeProviders.GROQ.failWith(401);
			FakeProviders.OPENROUTER.rateLimitedFor(1);
			FakeProviders.ZAI.failWith(404);

			assertThatThrownBy(chain::ask).isInstanceOfSatisfying(AllProvidersFailedException.class, ex -> {
				assertThat(String.valueOf(ex.getMessage())).doesNotContain("-key");
				for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
					assertThat(String.valueOf(cause.getMessage())).doesNotContain("-key").doesNotContain("PROVIDER-ERROR-BODY");
				}
			});

			var sent = List.of(FakeProviders.GEMINI, FakeProviders.GROQ, FakeProviders.OPENROUTER, FakeProviders.ZAI);
			List<String> keys = List.of("gemini-key", "groq-key", "openrouter-key", "zai-key");
			for (int i = 0; i < sent.size(); i++) {
				var request = sent.get(i).lastRequest();
				String everything = request.getHeaders().toString() + request.getBodyAsString() + request.getUrl();
				for (int other = 0; other < keys.size(); other++) {
					if (other == i) {
						assertThat(everything).contains(keys.get(other));
					}
					else {
						assertThat(everything).doesNotContain(keys.get(other));
					}
				}
			}
			assertThat(logs.texts()).isNotEmpty().noneMatch(text -> text.contains("-key") || text.contains("PROVIDER-ERROR-BODY"));
		}
	}

	@Test
	void twoProvidersWithTheSameKeyRefuseStartup() {
		assertThatThrownBy(() -> ProviderKit.create().order("groq", "openrouter").openAi("groq", FakeProviders.GROQ, "shared-secret")
				.openAi("openrouter", FakeProviders.OPENROUTER, "shared-secret").setting("openrouter", "model", "m:free").build())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("same API key").hasMessageNotContaining("shared-secret");
	}

	@Test
	void aBaseUrlWithAnEmptySegmentMeansNotConfigured() {
		// a Cloudflare account id that was never set leaves ".../accounts//ai/v1"
		chain = ProviderKit.create().order("cloudflare", "groq").openAi("groq", FakeProviders.GROQ).openAi("cloudflare", FakeProviders.ZAI)
				.setting("cloudflare", "base-url", "https://api.cloudflare.com/client/v4/accounts//ai/v1").build();

		assertThat(chain.registry().chain()).extracting(slot -> slot.settings().id()).containsExactly("groq");
	}

	@Test
	void aListedProviderWithoutAConfigurationBlockIsSkipped() {
		try (LogCapture logs = new LogCapture()) {
			chain = two().order("groq", "mystery", "openrouter").build();

			assertThat(chain.registry().chain()).extracting(slot -> slot.settings().id()).containsExactly("groq", "openrouter");
			assertThat(logs.has(Level.WARN, "mystery")).isTrue();
			assertThat(chain.registry().displayName("mystery")).isEqualTo("mystery"); // an id that is not configured shows as itself
		}
	}

	// ---- safety rules --------------------------------------------------------------------------

	@Test
	void openRouterRefusesToStartWithAModelThatIsNotFree() {
		assertThatThrownBy(() -> two().setting("openrouter", "model", "openai/gpt-4o").build()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("OpenRouter").hasMessageContaining(":free").hasMessageNotContaining("openrouter-key");
		// even without a key: a wrong model id is caught at startup, not when someone adds the key
		assertThatThrownBy(() -> two().setting("openrouter", "model", "openai/gpt-4o").setting("openrouter", "api-key", "").build())
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> two().setting("openrouter", "model", "").build()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void openRouterStartsWithAFreeModelAndAPaidOneIsHarmlessWhileItIsDisabled() {
		chain = two().setting("openrouter", "model", "google/gemma-4-31b-it:free").build();
		assertThat(chain.registry().chain()).hasSize(2);
		chain.close();

		chain = two().setting("openrouter", "model", "openai/gpt-4o").setting("openrouter", "enabled", "false").build();
		assertThat(chain.registry().chain()).extracting(slot -> slot.settings().id()).containsExactly("groq");
	}

	@Test
	void ovhIsOnlyEverCalledAnonymouslyEvenWhenAKeyIsConfigured() {
		try (LogCapture logs = new LogCapture()) {
			chain = ProviderKit.create().order("ovh").openAi("ovh", FakeProviders.OVH, "ovh-secret-key").setting("ovh", "auth", "bearer").build();

			assertThat(chain.registry().chain()).hasSize(1); // anonymous mode needs no key to be in the chain
			assertThat(chain.ask().provider()).isEqualTo("ovh");

			var request = FakeProviders.OVH.lastRequest();
			assertThat(request.getHeaders().keys()).noneMatch(name -> name.equalsIgnoreCase("Authorization"));
			assertThat(request.getHeaders().toString() + request.getBodyAsString()).doesNotContain("ovh-secret-key");
			assertThat(logs.has(Level.WARN, "ovh")).isTrue(); // the ignored key is reported (without the key)
			assertThat(logs.texts()).noneMatch(text -> text.contains("ovh-secret-key"));
		}
	}

	@Test
	void ovhWithoutAKeyIsInTheChainAndSendsNoAuthorizationHeader() {
		chain = ProviderKit.create().order("ovh").openAi("ovh", FakeProviders.OVH, null).build();

		assertThat(chain.ask().provider()).isEqualTo("ovh");
		assertThat(FakeProviders.OVH.lastRequest().getHeaders().keys()).noneMatch(name -> name.equalsIgnoreCase("Authorization"));
	}

	@Test
	void ovhCanBeSwitchedOff() {
		chain = ProviderKit.create().order("ovh").openAi("ovh", FakeProviders.OVH, null).setting("ovh", "enabled", "false").build();

		assertThat(chain.gateway().anyUsable()).isFalse();
	}

	@Test
	void anOptionalAuthProviderSendsABearerTokenOnlyWhenItHasAKey() {
		chain = ProviderKit.create().order("kilo").openAi("kilo", FakeProviders.NVIDIA, null).setting("kilo", "enabled", "true")
				.setting("kilo", "auth", "optional").build();
		chain.ask();
		assertThat(FakeProviders.NVIDIA.lastRequest().getHeaders().keys()).noneMatch(name -> name.equalsIgnoreCase("Authorization"));
		chain.close();

		chain = ProviderKit.create().order("kilo").openAi("kilo", FakeProviders.NVIDIA, "kilo-key").setting("kilo", "enabled", "true")
				.setting("kilo", "auth", "optional").build();
		chain.ask();
		assertThat(FakeProviders.NVIDIA.lastRequest().getHeader("Authorization")).isEqualTo("Bearer kilo-key");
	}

	@Test
	void optInProvidersAreInactiveWithoutAnExplicitEnabledTrue() {
		chain = two().openAi("nvidia-nim", FakeProviders.NVIDIA).build(); // has a key, but was never enabled
		FakeProviders.GROQ.failWith(500);
		FakeProviders.OPENROUTER.failWith(500);

		assertThat(chain.registry().chain()).extracting(slot -> slot.settings().id()).containsExactly("groq", "openrouter");
		assertThatThrownBy(chain::ask).isInstanceOf(AllProvidersFailedException.class);
		assertThat(FakeProviders.NVIDIA.requestCount()).isZero();
	}

	@Test
	void anEnabledOptInProviderJoinsTheChainAfterTheListedOnesAndIsWarnedAbout() {
		try (LogCapture logs = new LogCapture()) {
			chain = two().openAi("nvidia-nim", FakeProviders.NVIDIA).setting("nvidia-nim", "enabled", "true")
					.setting("nvidia-nim", "logs-prompts", "true").build();
			FakeProviders.GROQ.failWith(500);
			FakeProviders.OPENROUTER.failWith(500);
			FakeProviders.NVIDIA.answerWith("from nvidia");

			assertThat(chain.registry().chain()).extracting(slot -> slot.settings().id()).containsExactly("groq", "openrouter", "nvidia-nim");
			assertThat(chain.ask().provider()).isEqualTo("nvidia-nim");
			assertThat(logs.has(Level.WARN, "opt-in")).isTrue();
			assertThat(logs.has(Level.WARN, "may log prompts")).isTrue();
		}
	}

	@Test
	void cohereIsWarnedAboutAsNonCommercial() {
		try (LogCapture logs = new LogCapture()) {
			chain = ProviderKit.create().order("cohere").openAi("cohere", FakeProviders.COHERE).build();

			assertThat(logs.has(Level.WARN, "non-commercial")).isTrue();
			assertThat(logs.texts()).noneMatch(text -> text.contains("cohere-key"));
		}
	}

	// ---- local limits --------------------------------------------------------------------------

	@Test
	void theRequestsPerMinuteBucketStopsAProviderLocallyAndTheNextOneTakesOver() {
		chain = two().setting("groq", "requests-per-minute", "2").build();

		assertThat(chain.ask().provider()).isEqualTo("groq");
		assertThat(chain.ask().provider()).isEqualTo("groq");
		assertThat(chain.ask().provider()).isEqualTo("openrouter"); // groq's bucket is empty: no request is even sent

		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(2);
		assertThat(FakeProviders.OPENROUTER.requestCount()).isEqualTo(1);
	}

	@Test
	void maxConcurrentKeepsAProviderToOneCallInFlightAndTheNextQuestionGoesElsewhere() throws Exception {
		chain = two().setting("groq", "max-concurrent", "1").build();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FakeProviders.GROQ.holdRequests(entered, release);
		FakeProviders.GROQ.answerWith("slow groq");
		FakeProviders.OPENROUTER.answerWith("fast openrouter");

		CompletableFuture<LlmAnswer> first = CompletableFuture.supplyAsync(chain::ask);
		assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); // the first call is now in flight at groq

		assertThat(chain.ask().provider()).isEqualTo("openrouter"); // groq is full: skipped without a request
		release.countDown();
		assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(new LlmAnswer("slow groq", "groq"));
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(1); // only the first question reached it

		// the permit came back when the call ended
		FakeProviders.GROQ.holdRequests(new CountDownLatch(0), new CountDownLatch(0));
		assertThat(chain.ask().provider()).isEqualTo("groq");
	}

	@Test
	void aFullProviderCountsAsNotUsableInThePreFlightCheckWhenItIsTheOnlyOne() throws Exception {
		chain = ProviderKit.create().order("zai").openAi("zai", FakeProviders.ZAI).setting("zai", "max-concurrent", "1").build();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FakeProviders.ZAI.holdRequests(entered, release);

		CompletableFuture<LlmAnswer> first = CompletableFuture.supplyAsync(chain::ask);
		assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

		assertThat(chain.gateway().anyUsable()).isFalse();
		release.countDown();
		first.get(10, TimeUnit.SECONDS);
		assertThat(chain.gateway().anyUsable()).isTrue();
	}

	@Test
	void theDailyCounterStopsAProviderUntilTheNextUtcDay() {
		chain = two().setting("groq", "max-requests-per-day", "2").build();

		assertThat(chain.ask().provider()).isEqualTo("groq");
		assertThat(chain.ask().provider()).isEqualTo("groq");
		assertThat(chain.ask().provider()).isEqualTo("openrouter");
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(2);

		chain.clock().advance(Duration.ofDays(1));
		assertThat(chain.ask().provider()).isEqualTo("groq");
	}

	@Test
	void theMonthlyCounterStopsAProviderUntilTheNextUtcMonth() {
		chain = two().setting("groq", "max-requests-per-month", "1").build();

		assertThat(chain.ask().provider()).isEqualTo("groq");
		assertThat(chain.ask().provider()).isEqualTo("openrouter");
		chain.clock().advance(Duration.ofDays(5)); // a new day, but the same month
		assertThat(chain.ask().provider()).isEqualTo("openrouter");

		chain.clock().advance(Duration.ofDays(30)); // December
		assertThat(chain.ask().provider()).isEqualTo("groq");
	}

	@Test
	void everyAttemptCountsTowardsTheDailyAndMonthlyTotalsEvenWhenItFails() {
		chain = two().build();
		FakeProviders.GROQ.failWith(500);

		chain.ask();

		var groq = chain.registry().all().getFirst();
		assertThat(groq.guard().requestsToday()).isEqualTo(1);
		assertThat(groq.guard().requestsThisMonth()).isEqualTo(1);
	}

	// ---- cool-down -----------------------------------------------------------------------------

	@Test
	void aRateLimitedProviderIsNotAskedAgainUntilItsCoolDownEndsAndThenCoolsLongerIfItIsStillLimited() {
		chain = two().build();
		FakeProviders.GROQ.failWith(429); // no Retry-After: the default (30 s)

		assertThat(chain.ask().provider()).isEqualTo("openrouter");
		assertThat(Duration.between(chain.clock().instant(), chain.cooldowns().coolingUntil("groq"))).isEqualTo(Duration.ofSeconds(30));

		assertThat(chain.ask().provider()).isEqualTo("openrouter");
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(1); // not probed while cooling down

		chain.clock().advance(Duration.ofSeconds(31));
		assertThat(chain.ask().provider()).isEqualTo("openrouter");
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(2); // probed once more, still limited
		assertThat(Duration.between(chain.clock().instant(), chain.cooldowns().coolingUntil("groq"))).isEqualTo(Duration.ofSeconds(60)); // doubled

		chain.clock().advance(Duration.ofSeconds(61));
		FakeProviders.GROQ.reset();
		FakeProviders.GROQ.answerWith("back");
		assertThat(chain.ask().provider()).isEqualTo("groq"); // answered: the escalation starts over
		assertThat(chain.cooldowns().isCoolingDown("groq")).isFalse();
	}

	@Test
	void retryAfterSetsTheFirstCoolDown() {
		chain = two().build();
		FakeProviders.GROQ.rateLimitedFor(120);

		chain.ask();

		assertThat(Duration.between(chain.clock().instant(), chain.cooldowns().coolingUntil("groq"))).isEqualTo(Duration.ofSeconds(120));
	}

	@Test
	void whenEveryProviderIsCoolingDownNothingIsUsableAndNothingIsCalled() {
		chain = two().build();
		FakeProviders.GROQ.failWith(429);
		FakeProviders.OPENROUTER.failWith(429);
		assertThatThrownBy(chain::ask).isInstanceOf(AllProvidersFailedException.class);
		int groq = FakeProviders.GROQ.requestCount();
		int openRouter = FakeProviders.OPENROUTER.requestCount();

		assertThat(chain.gateway().anyUsable()).isFalse(); // the pre-flight check refuses here, before anything is charged
		assertThatThrownBy(chain::ask).isInstanceOf(AllProvidersFailedException.class);
		assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(groq);
		assertThat(FakeProviders.OPENROUTER.requestCount()).isEqualTo(openRouter);

		chain.clock().advance(Duration.ofSeconds(31));
		assertThat(chain.gateway().anyUsable()).isTrue();
	}

	@Test
	void aBadKeyPutsTheProviderInALongCoolDownWithALoudLogAndNeverACrash() {
		try (LogCapture logs = new LogCapture()) {
			chain = two().build();
			FakeProviders.GROQ.failWith(401);

			assertThat(chain.ask().provider()).isEqualTo("openrouter");

			assertThat(Duration.between(chain.clock().instant(), chain.cooldowns().coolingUntil("groq"))).isEqualTo(Duration.ofMinutes(30));
			assertThat(logs.has(Level.ERROR, "groq")).isTrue();
			chain.ask();
			assertThat(FakeProviders.GROQ.requestCount()).isEqualTo(1);
		}
	}

	@Test
	void aModelThatIsNotFoundPutsTheProviderInALongCoolDownWithALoudLog() {
		try (LogCapture logs = new LogCapture()) {
			chain = two().build();
			FakeProviders.GROQ.failWith(404);

			assertThat(chain.ask().provider()).isEqualTo("openrouter");

			assertThat(Duration.between(chain.clock().instant(), chain.cooldowns().coolingUntil("groq"))).isEqualTo(Duration.ofMinutes(30));
			assertThat(logs.has(Level.ERROR, "404")).isTrue();
		}
	}

	@Test
	void aServerErrorOrATimeoutDoesNotStartACoolDown() {
		chain = two().build();
		FakeProviders.GROQ.failWith(503);

		chain.ask();

		assertThat(chain.cooldowns().isCoolingDown("groq")).isFalse();
	}
}
