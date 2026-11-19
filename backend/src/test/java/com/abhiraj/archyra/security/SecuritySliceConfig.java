package com.abhiraj.archyra.security;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The real filter chain, JWT decoding and handlers for {@code @WebMvcTest} slices (which load only MVC
 * components). Controller tests import this and authenticate with {@code @WithMockUser} or a real token.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
@Import({ SecurityConfig.class, JwtConfig.class, JwtService.class, RestAuthenticationEntryPoint.class,
		RestAccessDeniedHandler.class, RateLimiter.class, ClientIpResolver.class, SecurityAuditLogger.class, RevokedSubjects.class })
public class SecuritySliceConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
