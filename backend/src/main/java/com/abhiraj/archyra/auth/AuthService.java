package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.auth.AuthDtos.LoginRequest;
import com.abhiraj.archyra.auth.AuthDtos.RegisterRequest;
import com.abhiraj.archyra.common.AccountDisabledException;
import com.abhiraj.archyra.common.EmailNotVerifiedException;
import com.abhiraj.archyra.common.InvalidCredentialsException;
import com.abhiraj.archyra.security.AccessToken;
import com.abhiraj.archyra.security.JwtService;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserProfile;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * Email and password accounts: register, login, refresh, logout. Whatever the outcome, nothing here reveals whether
 * an email is registered.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

	/** An issued access token plus the refresh token that goes into the cookie. */
	public record Session(AccessToken accessToken, RefreshTokenService.Issued refreshToken) {
	}

	private final UserService userService;
	private final AuthenticationManager authenticationManager;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokens;
	private final JwtService jwtService;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;
	private final AccountEmailFlows emailFlows;
	private final ExistingAccountFlows existingAccounts;
	private final Clock clock;

	/**
	 * Creates the account if the email is free. If it is not, {@link ExistingAccountFlows} handles it afterwards, off
	 * the request thread: the caller always answers the same 202 and nothing visible differs. The password is hashed
	 * either way, so the response time does not tell the two cases apart.
	 *
	 * @throws com.abhiraj.archyra.common.InvalidRequestException if the password is not acceptable (a rule that
	 *                                                           applies to everyone, so it reveals nothing)
	 */
	public void register(RegisterRequest request, ClientInfo client) {
		String email = UserService.normalizeEmail(request.email());
		PasswordPolicy.check(request.password(), email);
		String hash = passwordEncoder.encode(request.password());
		Optional<User> created = userService.registerIfAbsent(email, request.name().trim(), hash, clock.instant());
		if (created.isPresent()) {
			audit.event("USER_REGISTERED", created.get().getId(), client.ip());
			emailFlows.sendVerificationEmail(created.get());
		}
		else {
			existingAccounts.handle(email, client.ip());
		}
	}

	/**
	 * Checks, in order: lockout, credentials, enabled, email verified. A bad password, an unknown email and a locked
	 * account are all the same {@link InvalidCredentialsException}.
	 */
	public Session login(LoginRequest request, ClientInfo client) {
		String email = UserService.normalizeEmail(request.email());
		if (PasswordPolicy.exceedsHashLimit(request.password())) {
			throw new InvalidCredentialsException(); // cannot be a password we accepted; the encoder would throw
		}
		try {
			authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
		}
		catch (AuthenticationException ex) { // bad credentials, unknown email, or a locked account
			userService.findByEmail(email).ifPresent(user -> {
				boolean lockedNow = userService.recordFailedLogin(user.getId(),
						properties.lockout().maxFailedAttempts(), properties.lockout().lockDuration(), clock.instant());
				audit.warn(lockedNow ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", user.getId(), client.ip());
			});
			throw new InvalidCredentialsException();
		}

		User user = userService.findByEmail(email).orElseThrow(InvalidCredentialsException::new);
		if (!user.isEnabled()) {
			audit.warn("LOGIN_REFUSED_DISABLED", user.getId(), client.ip());
			throw new AccountDisabledException();
		}
		if (!user.isEmailVerified()) {
			throw new EmailNotVerifiedException();
		}
		userService.recordSuccessfulLogin(user.getId(), clock.instant());
		audit.event("LOGIN_SUCCESS", user.getId(), client.ip());
		return new Session(jwtService.issueFor(user), refreshTokens.startFamily(user.getId(), client));
	}

	/** Empty for any failure (unknown, expired or reused token, missing or disabled user); the caller answers 401. */
	public Optional<Session> refresh(String rawRefreshToken, ClientInfo client) {
		if (rawRefreshToken == null) {
			return Optional.empty();
		}
		Optional<RefreshTokenService.Rotation> rotation = refreshTokens.rotate(rawRefreshToken, client);
		if (rotation.isEmpty()) {
			return Optional.empty();
		}
		Optional<User> user = userService.findById(rotation.get().userId());
		if (user.isEmpty() || !user.get().isEnabled()) {
			refreshTokens.revokeAllForUser(rotation.get().userId());
			return Optional.empty();
		}
		audit.event("TOKEN_REFRESHED", user.get().getId(), client.ip());
		return Optional.of(new Session(jwtService.issueFor(user.get()), rotation.get().next()));
	}

	public void logout(String rawRefreshToken) {
		if (rawRefreshToken != null) {
			refreshTokens.revokeFamilyOf(rawRefreshToken);
		}
	}

	/** The signed-in user's own profile, or empty if the account no longer exists or was disabled. */
	public Optional<UserProfile> me(UUID userId) {
		return userService.profile(userId);
	}
}
