package com.abhiraj.archyra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.security.TestSecurityProperties;
import com.abhiraj.archyra.user.Role;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

	@Mock
	private UserService userService;
	@Mock
	private SecurityAuditLogger audit;
	private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

	private AdminBootstrapRunner runner(String email, String password) {
		SecurityProperties properties = TestSecurityProperties.withBootstrapAdmin(email, password);
		return new AdminBootstrapRunner(properties, userService, encoder, audit, clock);
	}

	@Test
	void createsAVerifiedEnabledAdministratorWhenNoneExists() {
		given(userService.adminExists()).willReturn(false);
		given(userService.findByEmail("boss@example.com")).willReturn(Optional.empty());
		given(userService.save(any(User.class))).willAnswer(call -> call.getArgument(0));

		runner("  Boss@Example.com ", "a long bootstrap passphrase").run(new DefaultApplicationArguments());

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userService).save(saved.capture());
		User admin = saved.getValue();
		assertThat(admin.getEmail()).isEqualTo("boss@example.com");
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.isEmailVerified()).isTrue();
		assertThat(admin.isEnabled()).isTrue();
		assertThat(admin.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("a long bootstrap passphrase");
		assertThat(encoder.matches("a long bootstrap passphrase", admin.getPasswordHash())).isTrue();
	}

	@Test
	void doesNothingWhenAnAdministratorAlreadyExists() {
		given(userService.adminExists()).willReturn(true);

		runner("boss@example.com", "a long bootstrap passphrase").run(new DefaultApplicationArguments());

		verify(userService, never()).save(any());
	}

	@Test
	void doesNothingWhenNotConfigured() {
		runner(null, null).run(new DefaultApplicationArguments());
		runner("", "").run(new DefaultApplicationArguments());
		runner("boss@example.com", "").run(new DefaultApplicationArguments());

		verifyNoMoreInteractions(userService);
	}

	@Test
	void neverPromotesAnExistingAccount() {
		given(userService.adminExists()).willReturn(false);
		given(userService.findByEmail("boss@example.com")).willReturn(Optional.of(User.fromSocialLogin("boss@example.com", "Boss")));

		runner("boss@example.com", "a long bootstrap passphrase").run(new DefaultApplicationArguments());

		verify(userService, never()).save(any());
	}

	@Test
	void aPasswordThatRegistrationWouldRefuseStopsTheStartup() {
		given(userService.adminExists()).willReturn(false);
		given(userService.findByEmail("boss@example.com")).willReturn(Optional.empty());

		assertThatThrownBy(() -> runner("boss@example.com", "password123").run(new DefaultApplicationArguments()))
				.isInstanceOf(IllegalStateException.class)
				.satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("password123"));
		verify(userService, never()).save(any());
	}
}
