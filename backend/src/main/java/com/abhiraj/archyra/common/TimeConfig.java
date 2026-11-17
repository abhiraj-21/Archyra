package com.abhiraj.archyra.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One {@link Clock} for the whole application, so tests can substitute a fixed one wherever time matters. */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
