package com.abhiraj.archyra.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.auth.AuthDtos.AuthResponse;
import com.abhiraj.archyra.auth.AuthDtos.CsrfResponse;
import com.abhiraj.archyra.auth.AuthDtos.EmailRequest;
import com.abhiraj.archyra.auth.AuthDtos.LoginRequest;
import com.abhiraj.archyra.auth.AuthDtos.MessageResponse;
import com.abhiraj.archyra.auth.AuthDtos.RegisterRequest;
import com.abhiraj.archyra.auth.AuthDtos.ResetPasswordRequest;
import com.abhiraj.archyra.auth.AuthDtos.VerifyEmailRequest;
import com.abhiraj.archyra.common.ErrorResponse;
import com.abhiraj.archyra.security.AccessToken;
import com.abhiraj.archyra.security.ClientIpResolver;
import com.abhiraj.archyra.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	static final String REGISTER_MESSAGE =
			"If this address can be registered, a verification email is on its way.";

	/** Same answer for every address, registered or not. */
	static final String GENERIC_EMAIL_MESSAGE = "If an account matches, an email is on its way.";

	private final AuthService authService;
	private final AccountEmailFlows emailFlows;
	private final RefreshCookies cookies;
	private final ClientIpResolver clientIps;

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public MessageResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
		authService.register(request, client(http));
		return new MessageResponse(REGISTER_MESSAGE);
	}

	@PostMapping("/verify-email")
	public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		emailFlows.verifyEmail(request.token());
		return new MessageResponse("Your email address is verified. You can sign in now.");
	}

	@PostMapping("/resend-verification")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public MessageResponse resendVerification(@Valid @RequestBody EmailRequest request) {
		emailFlows.resendVerification(request.email());
		return new MessageResponse(GENERIC_EMAIL_MESSAGE);
	}

	@PostMapping("/forgot-password")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest request) {
		emailFlows.forgotPassword(request.email());
		return new MessageResponse(GENERIC_EMAIL_MESSAGE);
	}

	/** Does not sign the user in; every existing session ends. */
	@PostMapping("/reset-password")
	public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
		emailFlows.resetPassword(request.token(), request.newPassword(), client(http));
		return new MessageResponse("Your password has been changed. Sign in with the new password.");
	}

	@PostMapping("/login")
	public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		return withSession(authService.login(request, client(http)));
	}

	/** Authenticated by the refresh cookie and protected by CSRF (see {@code SecurityConfig}). */
	@PostMapping("/refresh")
	public ResponseEntity<?> refresh(HttpServletRequest http) {
		return authService.refresh(cookies.read(http), client(http))
				.<ResponseEntity<?>>map(this::withSession)
				.orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
						.header(HttpHeaders.SET_COOKIE, cookies.clear())
						.body(new ErrorResponse("REFRESH_TOKEN_INVALID", "Please sign in again")));
	}

	/** Always 204: signing out twice, or with an expired session, is not an error. */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest http) {
		authService.logout(cookies.read(http));
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
	}

	/**
	 * Issues the {@code XSRF-TOKEN} cookie and also returns the token, because a SPA on another subdomain cannot
	 * read a cookie that belongs to the API host. Send it back as the {@code X-XSRF-TOKEN} header.
	 */
	@GetMapping("/csrf")
	public CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken()); // reading the token makes Security set the cookie
	}

	@GetMapping("/me")
	public ResponseEntity<?> me(Authentication authentication) {
		return CurrentUser.id(authentication).flatMap(authService::me)
				.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
						.body(new ErrorResponse("AUTHENTICATION_REQUIRED", "Authentication is required")));
	}

	private ResponseEntity<AuthResponse> withSession(AuthService.Session session) {
		AccessToken token = session.accessToken();
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken()))
				.body(new AuthResponse(token.value(), AccessToken.TOKEN_TYPE, token.expiresIn()));
	}

	private ClientInfo client(HttpServletRequest request) {
		return new ClientInfo(clientIps.resolve(request), request.getHeader(HttpHeaders.USER_AGENT));
	}
}
