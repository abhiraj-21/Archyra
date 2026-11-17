package com.abhiraj.archyra.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.user.User;

import lombok.RequiredArgsConstructor;

/**
 * The only place access tokens are created, so every login path (password, Google, GitHub) issues the same
 * kind of token.
 *
 * <p>Claims: {@code iss}, {@code sub} (the user id, never the email), {@code iat}, {@code exp}, {@code jti} and
 * {@code roles}. The plan is deliberately not a claim: a plan change (upgrade, expiry) must apply at once, so
 * it is read from the database whenever a premium feature is checked.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

	static final String ROLES_CLAIM = "roles";

	private final JwtEncoder jwtEncoder;
	private final SecurityProperties properties;
	private final Clock clock;

	public AccessToken issueFor(User user) {
		return issue(user.getId(), List.of(user.getRole().name()));
	}

	AccessToken issue(UUID userId, List<String> roles) {
		Instant now = clock.instant();
		var ttl = properties.jwt().accessTokenTtl();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.jwt().issuer())
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(now.plus(ttl))
				.id(UUID.randomUUID().toString())
				.claim(ROLES_CLAIM, roles)
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(token, ttl.toSeconds());
	}
}
