package com.abhiraj.archyra.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.auth.oauth2.SocialAccountResolver.Outcome;
import com.abhiraj.archyra.auth.oauth2.SocialAccountResolver.ProviderIdentity;
import com.abhiraj.archyra.auth.oauth2.SocialAccountResolver.Resolution;
import com.abhiraj.archyra.user.Provider;
import com.abhiraj.archyra.user.User;

import org.springframework.security.core.authority.AuthorityUtils;

/** What each provider's data is turned into before the account rules run. */
class SocialUserServicesTest {

	private final SocialAccountResolver resolver = mock(SocialAccountResolver.class);
	private final GitHubEmailClient emailClient = mock(GitHubEmailClient.class);

	private static ClientRegistration registration(String id) {
		return ClientRegistration.withRegistrationId(id).clientId("c").clientSecret("s")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost/cb").authorizationUri("http://localhost/auth").tokenUri("http://localhost/token")
				.userInfoUri("http://localhost/user").userNameAttributeName("id").scope("openid").build();
	}

	private static Resolution resolutionFor(UUID id) {
		User user = User.fromSocialLogin("a@example.com", "A");
		ReflectionTestUtils.setField(user, "id", id);
		return new Resolution(user, Outcome.CREATED);
	}

	// ---- Google (OpenID Connect) ------------------------------------------------------------------------

	private OidcUser googleUser(Map<String, Object> claims) {
		OidcIdToken token = new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(60), claims);
		return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), token);
	}

	private SocialOidcUserService oidcService(OidcUser delegateResult) {
		OAuth2UserService<OidcUserRequest, OidcUser> delegate = request -> delegateResult;
		return new SocialOidcUserService(delegate, resolver);
	}

	private OidcUserRequest oidcRequest(String registrationId) {
		OidcUserRequest request = mock(OidcUserRequest.class);
		given(request.getClientRegistration()).willReturn(registration(registrationId));
		return request;
	}

	@Test
	void googleClaimsAreHandedToTheResolver() {
		UUID id = UUID.randomUUID();
		var user = googleUser(Map.of("sub", "g-1", "email", "ada@example.com", "email_verified", true, "name", "Ada"));
		given(resolver.resolve(new ProviderIdentity(Provider.GOOGLE, "g-1", "ada@example.com", true, "Ada")))
				.willReturn(resolutionFor(id));

		OidcUser result = oidcService(user).loadUser(oidcRequest("google"));

		assertThat(result).isInstanceOfSatisfying(SocialOidcUser.class, principal -> {
			assertThat(principal.archyraUserId()).isEqualTo(id);
			assertThat(principal.provider()).isEqualTo(Provider.GOOGLE);
		});
	}

	@Test
	void aMissingEmailVerifiedClaimCountsAsNotVerified() {
		var user = googleUser(Map.of("sub", "g-1", "email", "ada@example.com"));
		given(resolver.resolve(new ProviderIdentity(Provider.GOOGLE, "g-1", "ada@example.com", false, null)))
				.willThrow(new SocialLoginException(SocialLoginErrors.EMAIL_UNVERIFIED, "x"));

		assertThatThrownBy(() -> oidcService(user).loadUser(oidcRequest("google")))
				.isInstanceOfSatisfying(OAuth2AuthenticationException.class,
						ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("oauth_email_unverified"));
	}

	@Test
	void anUnknownRegistrationIsAFailureNotACrash() {
		var user = googleUser(Map.of("sub", "g-1", "email", "ada@example.com", "email_verified", true));

		assertThatThrownBy(() -> oidcService(user).loadUser(oidcRequest("gitlab")))
				.isInstanceOfSatisfying(OAuth2AuthenticationException.class,
						ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("oauth_failed"));
		verify(resolver, never()).resolve(any());
	}

	// ---- GitHub (plain OAuth2) ----------------------------------------------------------------------------

	private SocialOAuth2UserService githubService(Map<String, Object> profile) {
		OAuth2User delegateResult = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_X"), profile, "id");
		OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = request -> delegateResult;
		return new SocialOAuth2UserService(delegate, emailClient, resolver);
	}

	private OAuth2UserRequest githubRequest() {
		OAuth2UserRequest request = mock(OAuth2UserRequest.class);
		given(request.getClientRegistration()).willReturn(registration("github"));
		given(request.getAccessToken()).willReturn(
				new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "gh-token", Instant.now(), Instant.now().plusSeconds(60)));
		return request;
	}

	@Test
	void githubUsesTheNumericIdAndThePrimaryVerifiedEmail() {
		UUID id = UUID.randomUUID();
		given(emailClient.primaryVerifiedEmail("gh-token")).willReturn(Optional.of("octo@example.com"));
		given(resolver.resolve(new ProviderIdentity(Provider.GITHUB, "4242", "octo@example.com", true, "Octo Cat")))
				.willReturn(resolutionFor(id));

		OAuth2User result = githubService(Map.of("id", 4242, "login", "octo", "name", "Octo Cat", "email", "public@example.com"))
				.loadUser(githubRequest());

		assertThat(result).isInstanceOfSatisfying(SocialOAuth2User.class, principal -> {
			assertThat(principal.archyraUserId()).isEqualTo(id);
			assertThat(principal.provider()).isEqualTo(Provider.GITHUB);
		});
	}

	@Test
	void githubFallsBackToTheLoginWhenThereIsNoName() {
		given(emailClient.primaryVerifiedEmail("gh-token")).willReturn(Optional.of("octo@example.com"));
		given(resolver.resolve(new ProviderIdentity(Provider.GITHUB, "4242", "octo@example.com", true, "octo")))
				.willReturn(resolutionFor(UUID.randomUUID()));

		githubService(Map.of("id", 4242, "login", "octo")).loadUser(githubRequest());

		verify(resolver).resolve(new ProviderIdentity(Provider.GITHUB, "4242", "octo@example.com", true, "octo"));
	}

	@Test
	void githubWithoutAPrimaryVerifiedEmailIsRefusedAndThePublicProfileEmailIsIgnored() {
		given(emailClient.primaryVerifiedEmail("gh-token")).willReturn(Optional.empty());
		given(resolver.resolve(new ProviderIdentity(Provider.GITHUB, "4242", null, false, "Octo Cat")))
				.willThrow(new SocialLoginException(SocialLoginErrors.EMAIL_UNVERIFIED, "x"));

		assertThatThrownBy(() -> githubService(Map.of("id", 4242, "name", "Octo Cat", "email", "public@example.com"))
				.loadUser(githubRequest()))
				.isInstanceOfSatisfying(OAuth2AuthenticationException.class,
						ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("oauth_email_unverified"));
	}

	@Test
	void aGithubProfileWithABlankIdIsAFailure() {
		assertThatThrownBy(() -> githubService(Map.of("login", "octo", "id", "")).loadUser(githubRequest()))
				.isInstanceOfSatisfying(OAuth2AuthenticationException.class,
						ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("oauth_failed"));
		verify(resolver, never()).resolve(any());
	}
}
