package com.abhiraj.archyra.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.auth.RefreshTokenService;
import com.abhiraj.archyra.auth.oauth2.SocialAccountResolver.Outcome;
import com.abhiraj.archyra.auth.oauth2.SocialAccountResolver.ProviderIdentity;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.user.LinkedIdentity;
import com.abhiraj.archyra.user.Provider;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

@ExtendWith(MockitoExtension.class)
class SocialAccountResolverTest {

	private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");

	@Mock
	private UserService userService;
	@Mock
	private RefreshTokenService refreshTokens;
	@Mock
	private SecurityAuditLogger audit;

	private SocialAccountResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = new SocialAccountResolver(userService, refreshTokens, audit, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static User withId(User user) {
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

	private static ProviderIdentity google(String sub, String email, boolean verified) {
		return new ProviderIdentity(Provider.GOOGLE, sub, email, verified, "Ada Lovelace");
	}

	@Test
	void aNewUserIsCreatedVerifiedAndLinked() {
		given(userService.findIdentity(Provider.GOOGLE, "sub-1")).willReturn(Optional.empty());
		given(userService.findByEmail("ada@example.com")).willReturn(Optional.empty());
		given(userService.createSocialUser("ada@example.com", "Ada Lovelace"))
				.willAnswer(call -> withId(User.fromSocialLogin("ada@example.com", "Ada Lovelace")));

		var resolution = resolver.resolve(google("sub-1", "Ada@Example.com", true));

		assertThat(resolution.outcome()).isEqualTo(Outcome.CREATED);
		assertThat(resolution.user().isEmailVerified()).isTrue();
		assertThat(resolution.user().hasPassword()).isFalse();
		verify(userService).link(resolution.user(), Provider.GOOGLE, "sub-1", "ada@example.com");
	}

	@Test
	void theNameFallsBackToTheEmailLocalPart() {
		given(userService.findIdentity(Provider.GITHUB, "7")).willReturn(Optional.empty());
		given(userService.findByEmail("octo@example.com")).willReturn(Optional.empty());
		given(userService.createSocialUser("octo@example.com", "octo"))
				.willAnswer(call -> withId(User.fromSocialLogin("octo@example.com", "octo")));

		resolver.resolve(new ProviderIdentity(Provider.GITHUB, "7", "octo@example.com", true, "  "));

		verify(userService).createSocialUser("octo@example.com", "octo");
	}

	@Test
	void aKnownIdentityWinsEvenIfTheEmailChanged() {
		User user = withId(User.fromSocialLogin("old@example.com", "Ada"));
		given(userService.findIdentity(Provider.GOOGLE, "sub-1"))
				.willReturn(Optional.of(new LinkedIdentity(user.getId(), Provider.GOOGLE, "sub-1", "old@example.com")));
		given(userService.findById(user.getId())).willReturn(Optional.of(user));

		var resolution = resolver.resolve(google("sub-1", "new@example.com", true));

		assertThat(resolution.outcome()).isEqualTo(Outcome.EXISTING_IDENTITY);
		assertThat(resolution.user()).isSameAs(user);
		verify(userService, never()).findByEmail(anyString());
		verify(userService, never()).createSocialUser(anyString(), anyString());
	}

	@Test
	void aVerifiedAccountWithTheSameEmailIsLinkedWithoutTouchingItsPassword() {
		User user = withId(User.registered("ada@example.com", "Ada", "{bcrypt}hash", NOW));
		user.markEmailVerified();
		given(userService.findIdentity(Provider.GOOGLE, "sub-1")).willReturn(Optional.empty());
		given(userService.findByEmail("ada@example.com")).willReturn(Optional.of(user));
		given(userService.linkedProviders(user.getId())).willReturn(List.of());
		given(userService.findById(user.getId())).willReturn(Optional.of(user));

		var resolution = resolver.resolve(google("sub-1", "ada@example.com", true));

		assertThat(resolution.outcome()).isEqualTo(Outcome.LINKED);
		assertThat(resolution.user().getId()).isEqualTo(user.getId());
		verify(userService).link(user, Provider.GOOGLE, "sub-1", "ada@example.com");
		verify(userService, never()).takeOverUnverifiedAccount(any(), any());
		verify(refreshTokens, never()).revokeAllForUser(any());
	}

	@Test
	void anUnverifiedPasswordAccountIsTakenOverAndItsSessionsEnded() {
		User squatter = withId(User.registered("victim@example.com", "Squatter", "{bcrypt}attackers-password", NOW));
		given(userService.findIdentity(Provider.GOOGLE, "sub-9")).willReturn(Optional.empty());
		given(userService.findByEmail("victim@example.com")).willReturn(Optional.of(squatter));
		given(userService.linkedProviders(squatter.getId())).willReturn(List.of());
		given(userService.findById(squatter.getId())).willReturn(Optional.of(squatter));

		var resolution = resolver.resolve(google("sub-9", "victim@example.com", true));

		assertThat(resolution.outcome()).isEqualTo(Outcome.LINKED);
		verify(userService).link(squatter, Provider.GOOGLE, "sub-9", "victim@example.com");
		verify(userService).takeOverUnverifiedAccount(squatter.getId(), NOW);
		verify(refreshTokens).revokeAllForUser(squatter.getId());
	}

	@Test
	void anUnverifiedProviderEmailIsRefusedBeforeAnythingIsLooked_up() {
		assertThatThrownBy(() -> resolver.resolve(google("sub-1", "ada@example.com", false)))
				.isInstanceOfSatisfying(SocialLoginException.class,
						ex -> assertThat(ex.code()).isEqualTo(SocialLoginErrors.EMAIL_UNVERIFIED));
		verifyNoMoreInteractions(userService, refreshTokens);
	}

	@Test
	void aMissingEmailIsRefused() {
		assertThatThrownBy(() -> resolver.resolve(new ProviderIdentity(Provider.GITHUB, "7", null, false, "x")))
				.isInstanceOfSatisfying(SocialLoginException.class,
						ex -> assertThat(ex.code()).isEqualTo(SocialLoginErrors.EMAIL_UNVERIFIED));
	}

	@Test
	void aDisabledLinkedAccountIsRefused() {
		User user = withId(User.fromSocialLogin("ada@example.com", "Ada"));
		user.setEnabled(false);
		given(userService.findIdentity(Provider.GOOGLE, "sub-1"))
				.willReturn(Optional.of(new LinkedIdentity(user.getId(), Provider.GOOGLE, "sub-1", "ada@example.com")));
		given(userService.findById(user.getId())).willReturn(Optional.of(user));

		assertThatThrownBy(() -> resolver.resolve(google("sub-1", "ada@example.com", true)))
				.isInstanceOfSatisfying(SocialLoginException.class,
						ex -> assertThat(ex.code()).isEqualTo(SocialLoginErrors.ACCOUNT_DISABLED));
	}

	@Test
	void aDisabledAccountFoundByEmailIsRefusedAndNotLinked() {
		User user = withId(User.registered("ada@example.com", "Ada", "{bcrypt}hash", NOW));
		user.setEnabled(false);
		given(userService.findIdentity(Provider.GOOGLE, "sub-1")).willReturn(Optional.empty());
		given(userService.findByEmail("ada@example.com")).willReturn(Optional.of(user));

		assertThatThrownBy(() -> resolver.resolve(google("sub-1", "ada@example.com", true)))
				.isInstanceOfSatisfying(SocialLoginException.class,
						ex -> assertThat(ex.code()).isEqualTo(SocialLoginErrors.ACCOUNT_DISABLED));
		verify(userService, never()).link(any(), any(), anyString(), anyString());
	}

	@Test
	void anAccountAlreadyTiedToAnotherAccountOfTheSameProviderIsNotRelinked() {
		User user = withId(User.fromSocialLogin("ada@example.com", "Ada"));
		given(userService.findIdentity(Provider.GOOGLE, "someone-else")).willReturn(Optional.empty());
		given(userService.findByEmail("ada@example.com")).willReturn(Optional.of(user));
		given(userService.linkedProviders(user.getId())).willReturn(List.of(Provider.GOOGLE));

		assertThatThrownBy(() -> resolver.resolve(google("someone-else", "ada@example.com", true)))
				.isInstanceOfSatisfying(SocialLoginException.class,
						ex -> assertThat(ex.code()).isEqualTo(SocialLoginErrors.FAILED));
		verify(userService, never()).link(any(), any(), anyString(), anyString());
	}

	@Test
	void aLongProviderNameIsCutToTheColumnSize() {
		given(userService.findIdentity(Provider.GOOGLE, "s")).willReturn(Optional.empty());
		given(userService.findByEmail("ada@example.com")).willReturn(Optional.empty());
		given(userService.createSocialUser(anyString(), anyString()))
				.willAnswer(call -> withId(User.fromSocialLogin("ada@example.com", call.getArgument(1))));

		var resolution = resolver.resolve(new ProviderIdentity(Provider.GOOGLE, "s", "ada@example.com", true, "n".repeat(300)));

		assertThat(resolution.user().getName()).hasSize(100);
	}
}
