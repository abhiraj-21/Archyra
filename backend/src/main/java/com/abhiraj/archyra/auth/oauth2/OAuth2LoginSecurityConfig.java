package com.abhiraj.archyra.auth.oauth2;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityConfig;
import com.abhiraj.archyra.security.SecurityProperties;

/**
 * Filter chain 1 of 2: only {@code /oauth2/**} (start of the login) and {@code /login/oauth2/**} (the provider's
 * callback). It is the one place with a session, kept only to hold the authorization request (state, nonce)
 * during the handshake. The signed-in principal is never stored in it, and both handlers destroy it. Everything
 * else is the stateless chain in {@link SecurityConfig}.
 *
 * <p>An HTTP session was chosen over a signed or encrypted cookie for the authorization request because it is
 * Spring's tested default, holds nothing after the handshake, and needs no extra key management.
 */
@Configuration(proxyBeanMethods = false)
class OAuth2LoginSecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain oauth2LoginFilterChain(HttpSecurity http, SocialOidcUserService oidcUserService,
			SocialOAuth2UserService oauth2UserService, OAuth2LoginSuccessHandler successHandler,
			OAuth2LoginFailureHandler failureHandler, LinkIntentService linkIntents, LinkBindingCookie linkBindingCookie,
			SecurityProperties properties, SecurityAuditLogger audit) {
		http
				// Decides login versus link before Spring redirects to the provider (see LinkAuthorizationFilter).
				.addFilterBefore(new LinkAuthorizationFilter(linkIntents, linkBindingCookie, properties, audit),
						OAuth2AuthorizationRequestRedirectFilter.class)
				.securityMatcher("/oauth2/**", "/login/oauth2/**")
				.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
				.oauth2Login(login -> login
						.userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService).userService(oauth2UserService))
						.successHandler(successHandler)
						.failureHandler(failureHandler))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				.securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
				.csrf(AbstractHttpConfigurer::disable) // GET redirects only: nothing here changes state on a forged request
				.requestCache(cache -> cache.disable())
				.headers(SecurityConfig::applyHeaders);
		return http.build();
	}
}
