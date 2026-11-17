package com.abhiraj.archyra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.user.Role;
import com.abhiraj.archyra.user.User;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

class JwtServiceTest {

	private static final Instant START = Instant.parse("2026-03-01T12:00:00Z");
	private static final String SECRET = secret("archyra-test-only-secret-key-32b");
	private static final String OTHER_SECRET = secret("another-test-only-secret-key-32b");

	private MutableClock clock;
	private JwtService service;
	private JwtDecoder decoder;
	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		clock = new MutableClock(START);
		SecurityProperties properties = properties(SECRET, "archyra");
		service = new JwtService(encoder(properties), properties, clock);
		decoder = decoder(properties);
	}

	@Test
	void anIssuedTokenCarriesTheExpectedClaims() {
		AccessToken token = service.issue(userId, List.of("USER"));

		Jwt jwt = decoder.decode(token.value());

		assertThat(jwt.getClaimAsString("iss")).isEqualTo("archyra"); // not a URL, so not getIssuer()
		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getIssuedAt()).isEqualTo(START);
		assertThat(jwt.getExpiresAt()).isEqualTo(START.plus(Duration.ofMinutes(15)));
		assertThat(UUID.fromString(jwt.getId())).isNotNull();
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
		assertThat(token.expiresIn()).isEqualTo(900);
	}

	@Test
	void theTokenNeverCarriesThePlanOrTheEmail() {
		User user = User.registered("ada@example.com", "Ada", "{bcrypt}x", START);
		ReflectionTestUtils.setField(user, "id", userId);

		Jwt jwt = decoder.decode(service.issueFor(user).value());

		assertThat(jwt.getClaims()).doesNotContainKeys("plan", "email", "name");
		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly(Role.USER.name());
	}

	@Test
	void everyTokenHasItsOwnId() {
		Jwt first = decoder.decode(service.issue(userId, List.of("USER")).value());
		Jwt second = decoder.decode(service.issue(userId, List.of("USER")).value());

		assertThat(first.getId()).isNotEqualTo(second.getId());
	}

	@Test
	void anExpiredTokenIsRejectedOnceTheClockSkewIsUsedUp() {
		String token = service.issue(userId, List.of("USER")).value();

		clock.set(START.plus(Duration.ofMinutes(15)).plusSeconds(29));
		assertThat(decoder.decode(token)).isNotNull(); // within the 30 s skew

		clock.set(START.plus(Duration.ofMinutes(15)).plusSeconds(31));
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class).hasMessageContaining("expired");
	}

	@Test
	void aTokenSignedWithAnotherSecretIsRejected() {
		SecurityProperties other = properties(OTHER_SECRET, "archyra");
		String forged = new JwtService(encoder(other), other, clock).issue(userId, List.of("ADMIN")).value();

		assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void aTokenFromAnotherIssuerIsRejected() {
		SecurityProperties other = properties(SECRET, "someone-else");
		String foreign = new JwtService(encoder(other), other, clock).issue(userId, List.of("USER")).value();

		assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void aTamperedPayloadIsRejected() {
		String[] parts = service.issue(userId, List.of("USER")).value().split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
				.replace("[\"USER\"]", "[\"ADMIN\"]");
		String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

		assertThat(payload).contains("ADMIN");
		assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void anUnsignedTokenIsRejected() {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("archyra").subject(userId.toString())
				.expirationTime(java.util.Date.from(START.plus(Duration.ofHours(1)))).claim("roles", List.of("ADMIN")).build();
		String unsigned = new PlainJWT(claims).serialize();

		assertThatThrownBy(() -> decoder.decode(unsigned)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void aTokenWithoutAnExpiryIsRejected() throws Exception {
		var key = new com.nimbusds.jose.crypto.MACSigner(JwtConfig.secretKey(properties(SECRET, "archyra")));
		var claims = new JWTClaimsSet.Builder().issuer("archyra").subject(userId.toString()).build();
		var signed = new com.nimbusds.jwt.SignedJWT(
				new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
		signed.sign(key);

		assertThatThrownBy(() -> decoder.decode(signed.serialize())).isInstanceOf(BadJwtException.class);
	}

	@Test
	void garbageIsRejected() {
		assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(BadJwtException.class);
	}

	@Test
	void rolesBecomeRolePrefixedAuthorities() {
		Jwt jwt = decoder.decode(service.issue(userId, List.of("USER", "ADMIN")).value());

		var authentication = new JwtConfig().jwtAuthenticationConverter().convert(jwt);

		assertThat(authentication.getName()).isEqualTo(userId.toString());
		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.contains("ROLE_USER", "ROLE_ADMIN") // Security 7 also adds a FACTOR_BEARER authority
				.doesNotContain("USER", "ADMIN");
	}

	// ---- helpers -------------------------------------------------------------------------------------

	private JwtEncoder encoder(SecurityProperties properties) {
		return new JwtConfig().jwtEncoder(properties);
	}

	private JwtDecoder decoder(SecurityProperties properties) {
		return new JwtConfig().jwtDecoder(properties, clock);
	}

	static SecurityProperties properties(String secret, String issuer) {
		return new SecurityProperties(
				new SecurityProperties.Jwt(secret, issuer, Duration.ofMinutes(15)),
				new SecurityProperties.RefreshToken(Duration.ofDays(7), "refresh_token", "/api/v1/auth", true),
				Duration.ofHours(24), Duration.ofMinutes(30), "http://localhost:5173",
				new SecurityProperties.Cors(List.of("http://localhost:5173")),
				new SecurityProperties.OAuth2("/auth/callback", "/login"),
				new SecurityProperties.RateLimit(10, 5, 10),
				new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
				new SecurityProperties.BootstrapAdmin(null, null));
	}

	private static String secret(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	/** A clock the test can move. */
	static final class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void set(Instant instant) {
			this.now = instant;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
