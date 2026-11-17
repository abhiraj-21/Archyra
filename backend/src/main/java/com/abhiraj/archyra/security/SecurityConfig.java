package com.abhiraj.archyra.security;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.RequiredArgsConstructor;

/**
 * The API's filter chain: stateless, authenticated by an access token (OAuth2 resource server), with the
 * authorization rules below. The OAuth2 login chain (Order 1) is added in a later phase and matches only
 * {@code /oauth2/**} and {@code /login/oauth2/**}; this chain handles everything else.
 *
 * <pre>
 * /api/v1/auth/me      authenticated
 * /api/v1/auth/**      public (refresh and logout are guarded by cookie + CSRF, not by the token)
 * /api/v1/admin/**     ADMIN
 * /error               public
 * /api/v1/**           authenticated (ownership and premium checks happen in the services)
 * anything else        denied
 * </pre>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final SecurityProperties properties;

	@Bean
	@Order(2)
	SecurityFilterChain apiFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
			RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler accessDeniedHandler, RateLimiter rateLimiter,
			ClientIpResolver clientIps, SecurityAuditLogger audit) {
		http
				// after CORS (so a browser can read the 429) and before anything that does real work
				.addFilterAfter(new RateLimitFilter(rateLimiter, clientIps, audit), CorsFilter.class)
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/api/v1/auth/me").authenticated()
						.requestMatchers("/api/v1/auth/**").permitAll()
						.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
						.requestMatchers("/error").permitAll()
						.requestMatchers("/api/v1/**").authenticated()
						.anyRequest().denyAll())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
						.authenticationEntryPoint(entryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(entryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				// Bearer-authenticated requests are not CSRF-prone, so only the two endpoints that are authenticated
				// by the refresh cookie are protected. SameSite=Strict on the cookie is a second layer.
				// The plain handler, not csrf.spa(): spa() writes the XSRF-TOKEN cookie on every response, even plain
				// bearer API calls. Here only GET /api/v1/auth/csrf writes it, and the header carries the raw token.
				.csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
						.csrfTokenRepository(csrfTokenRepository())
						.requireCsrfProtectionMatcher(new OrRequestMatcher(
								PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/refresh"),
								PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/logout"))))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.disable())
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.headers(SecurityConfig::applyHeaders);
		return http.build();
	}

	/** HSTS (one year, subdomains), no referrer, and a CSP that allows nothing: API responses are data, not pages. */
	public static void applyHeaders(HeadersConfigurer<HttpSecurity> headers) {
		headers
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(Duration.ofDays(365).toSeconds()))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"));
	}

	/**
	 * The {@code XSRF-TOKEN} cookie must be readable by the SPA's JavaScript, so it is not HttpOnly and its path is
	 * {@code /}: a page can only read cookies whose path covers the page's own path. Same-site and Secure like the
	 * refresh cookie.
	 */
	private CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict").path("/")
				.secure(properties.refreshToken().cookieSecure()));
		return repository;
	}

	/** Exact origins from configuration, credentials allowed (the refresh cookie), never a wildcard. */
	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.cors().allowedOrigins());
		cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
		cors.setExposedHeaders(List.of("Retry-After"));
		cors.setAllowCredentials(true);
		cors.setMaxAge(Duration.ofHours(1));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

	/**
	 * Node and file ids contain slashes and arrive percent-encoded ({@code file%3Asrc%2FApp.java}); see
	 * {@code common/TomcatConfig}. Spring Security's strict firewall rejects an encoded slash by default, so
	 * only the encoded slash is relaxed. Because Tomcat hands the path over still encoded ("passthrough"), the
	 * firewall also sees a literal {@code %} in the decoded path, so encoded percent must be allowed as well.
	 * Everything else (encoded period, backslash, semicolon, non-normalised paths, ...) stays blocked, and
	 * access rules match on fixed {@code /api/v1/...} prefixes, never on the id segment.
	 */
	@Bean
	HttpFirewall httpFirewall() {
		StrictHttpFirewall firewall = new StrictHttpFirewall();
		firewall.setAllowUrlEncodedSlash(true);
		firewall.setAllowUrlEncodedPercent(true);
		return firewall;
	}
}
