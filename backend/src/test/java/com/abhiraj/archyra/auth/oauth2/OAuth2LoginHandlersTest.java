package com.abhiraj.archyra.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import com.abhiraj.archyra.auth.ClientInfo;
import com.abhiraj.archyra.auth.RefreshCookies;
import com.abhiraj.archyra.auth.RefreshTokenService;
import com.abhiraj.archyra.security.ClientIpResolver;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.security.TestSecurityProperties;
import com.abhiraj.archyra.user.Provider;
import com.abhiraj.archyra.user.UserService;

class OAuth2LoginHandlersTest {

	private final SecurityProperties properties = TestSecurityProperties.create(); // frontend: https://app.example.com/

	// ---- failure handler -----------------------------------------------------------------------------------

	private OAuth2LoginFailureHandler failureHandler() {
		return new OAuth2LoginFailureHandler(properties);
	}

	private static OAuth2AuthenticationException oauthError(String code) {
		return new OAuth2AuthenticationException(new OAuth2Error(code, "provider said: secret details", null));
	}

	@Test
	void errorCodesMapToTheFixedSet() {
		assertThat(OAuth2LoginFailureHandler.codeFor(oauthError("oauth_email_unverified"))).isEqualTo("oauth_email_unverified");
		assertThat(OAuth2LoginFailureHandler.codeFor(oauthError("oauth_account_disabled"))).isEqualTo("oauth_account_disabled");
		assertThat(OAuth2LoginFailureHandler.codeFor(oauthError("access_denied"))).isEqualTo("oauth_cancelled");
		assertThat(OAuth2LoginFailureHandler.codeFor(oauthError("invalid_token_response"))).isEqualTo("oauth_failed");
		assertThat(OAuth2LoginFailureHandler.codeFor(oauthError("<script>"))).isEqualTo("oauth_failed");
		assertThat(OAuth2LoginFailureHandler.codeFor(new BadCredentialsException("password=hunter2"))).isEqualTo("oauth_failed");
	}

	@Test
	void theFailureRedirectCarriesOnlyTheFixedCodeAndDestroysTheSession() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		request.setParameter("redirect_uri", "https://evil.example");
		request.setParameter("error_description", "leak me");
		MockHttpServletResponse response = new MockHttpServletResponse();

		failureHandler().onAuthenticationFailure(request, response, oauthError("access_denied"));

		assertThat(response.getRedirectedUrl()).isEqualTo("https://app.example.com/login?error=oauth_cancelled");
		assertThat(session.isInvalid()).isTrue();
		assertThat(response.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("JSESSIONID=;") && c.contains("Max-Age=0"));
	}

	// ---- success handler -----------------------------------------------------------------------------------

	@Test
	void successSetsTheRefreshCookieDestroysTheSessionAndRedirectsWithoutSecrets() throws Exception {
		UUID userId = UUID.randomUUID();
		RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
		UserService userService = mock(UserService.class);
		var issued = new RefreshTokenService.Issued("raw-refresh-token", Instant.parse("2026-07-08T10:00:00Z"));
		given(refreshTokens.startFamily(any(UUID.class), any(ClientInfo.class))).willReturn(issued);
		var handler = new OAuth2LoginSuccessHandler(refreshTokens, new RefreshCookies(properties), userService, properties,
				mock(SecurityAuditLogger.class), failureHandler(), new ClientIpResolver(), Clock.systemUTC());
		SocialPrincipal principal = new SocialPrincipal() {
			public UUID archyraUserId() {
				return userId;
			}

			public Provider provider() {
				return Provider.GOOGLE;
			}
		};
		Authentication authentication = mock(Authentication.class);
		given(authentication.getPrincipal()).willReturn(principal);

		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpSession session = new MockHttpSession();
		request.setSession(session);
		request.setParameter("redirect_uri", "https://evil.example");
		request.setParameter("continue", "https://evil.example");
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, authentication);

		assertThat(response.getRedirectedUrl()).isEqualTo("https://app.example.com/auth/callback").doesNotContain("?");
		assertThat(response.getRedirectedUrl()).doesNotContain("raw-refresh-token");
		List<String> cookies = response.getHeaders("Set-Cookie");
		assertThat(cookies).anyMatch(c -> c.startsWith("refresh_token=raw-refresh-token") && c.contains("HttpOnly")
				&& c.contains("SameSite=Strict") && c.contains("Secure") && c.contains("Path=/api/v1/auth"));
		assertThat(cookies).anyMatch(c -> c.startsWith("JSESSIONID=;") && c.contains("Max-Age=0"));
		assertThat(session.isInvalid()).isTrue();
		verify(userService).recordSuccessfulLogin(any(UUID.class), any(Instant.class));
	}

	@Test
	void aPrincipalThatIsNotOursEndsAsAFailure() throws Exception {
		var handler = new OAuth2LoginSuccessHandler(mock(RefreshTokenService.class), new RefreshCookies(properties),
				mock(UserService.class), properties, mock(SecurityAuditLogger.class), failureHandler(), new ClientIpResolver(), Clock.systemUTC());
		Authentication authentication = mock(Authentication.class);
		given(authentication.getPrincipal()).willReturn("someone");
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getRedirectedUrl()).isEqualTo("https://app.example.com/login?error=oauth_failed");
		assertThat(response.getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("refresh_token="));
	}
}
