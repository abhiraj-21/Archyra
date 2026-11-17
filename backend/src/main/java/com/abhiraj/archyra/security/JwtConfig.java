package com.abhiraj.archyra.security;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * HS256 access tokens signed with the shared secret, on Spring Security's own Nimbus support (no hand-rolled
 * filter, no extra JWT library). Validation checks the signature and algorithm, the expiry (at most 30
 * seconds of clock skew) and the issuer.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

	static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

	@Bean
	JwtEncoder jwtEncoder(SecurityProperties properties) {
		return NimbusJwtEncoder.withSecretKey(secretKey(properties)).algorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtDecoder jwtDecoder(SecurityProperties properties, Clock clock) {
		return jwtDecoder(properties, clock, secretKey(properties));
	}

	/** Grants {@code ROLE_<role>} for every entry of the {@code roles} claim, so {@code hasRole("ADMIN")} works. */
	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(JwtService.ROLES_CLAIM);
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

	static JwtDecoder jwtDecoder(SecurityProperties properties, Clock clock, SecretKey key) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
		timestamps.setClock(clock);
		timestamps.setAllowEmptyExpiryClaim(false); // a token that never expires is not one of ours
		OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
				List.of(timestamps, new JwtIssuerValidator(properties.jwt().issuer())));
		decoder.setJwtValidator(validator);
		return decoder;
	}

	static SecretKey secretKey(SecurityProperties properties) {
		return new SecretKeySpec(properties.jwt().secretBytes(), "HmacSHA256");
	}
}
