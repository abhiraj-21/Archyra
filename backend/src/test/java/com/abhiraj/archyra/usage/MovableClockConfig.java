package com.abhiraj.archyra.usage;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.abhiraj.archyra.ai.llm.MovableClock;

/**
 * Replaces the application's {@code Clock} with one a test moves by hand (the weekly reset needs time to pass without
 * sleeping). It is the one {@code Clock} everything gets, JWT and plan expiry included, so they all agree. Import it from
 * a test, set the time in {@code @BeforeEach} and never run such a test class against a context that needs real time to
 * pass (provider cool-downs do).
 */
@TestConfiguration(proxyBeanMethods = false)
public class MovableClockConfig {

	@Bean
	@Primary
	MovableClock movableClock() {
		MovableClock clock = new MovableClock();
		clock.setTo(Instant.now());
		return clock;
	}
}
