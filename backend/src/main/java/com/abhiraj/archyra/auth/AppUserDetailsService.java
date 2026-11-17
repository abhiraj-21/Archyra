package com.abhiraj.archyra.auth;

import java.time.Clock;
import java.util.UUID;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

/**
 * Loads an account by email for {@code DaoAuthenticationProvider}. Two details keep login from leaking anything:
 * <ul>
 * <li>An account with no password (social login only) gets a hash nobody knows, so the comparison still costs
 * the same and fails like any wrong password.</li>
 * <li>{@code enabled} is always true here. A disabled or unverified account is reported by {@code AuthService}
 * only after the password was right; otherwise a wrong guess would reveal that the account exists.</li>
 * </ul>
 * A currently locked account is reported as locked, which {@code AuthService} turns into the generic failure.
 */
@Service
class AppUserDetailsService implements UserDetailsService {

	private final UserService userService;
	private final Clock clock;
	private final String unusableHash;

	AppUserDetailsService(UserService userService, Clock clock, PasswordEncoder passwordEncoder) {
		this.userService = userService;
		this.clock = clock;
		this.unusableHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	@Override
	public UserDetails loadUserByUsername(String email) {
		User user = userService.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("unknown"));
		return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
				.password(user.hasPassword() ? user.getPasswordHash() : unusableHash)
				.roles(user.getRole().name())
				.accountLocked(user.isLocked(clock.instant()))
				.build();
	}
}
