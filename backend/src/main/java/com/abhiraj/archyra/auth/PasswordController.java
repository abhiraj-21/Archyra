package com.abhiraj.archyra.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.auth.AuthDtos.AuthResponse;
import com.abhiraj.archyra.auth.AuthDtos.ChangePasswordRequest;
import com.abhiraj.archyra.common.UserNotFoundException;
import com.abhiraj.archyra.security.AccessToken;
import com.abhiraj.archyra.security.ClientIpResolver;
import com.abhiraj.archyra.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The signed-in user's password (Settings → Password). Bearer-authenticated like the rest of {@code /users/me}. The
 * answer is a new session in the shape of a login: the refresh cookie of the caller is replaced because every other
 * session of the user has just ended.
 */
@RestController
@RequestMapping("/api/v1/users/me/password")
@RequiredArgsConstructor
public class PasswordController {

	private final PasswordChangeService passwordChanges;
	private final RefreshCookies cookies;
	private final ClientIpResolver clientIps;

	@PostMapping
	public ResponseEntity<AuthResponse> change(Authentication authentication, @Valid @RequestBody ChangePasswordRequest request,
			HttpServletRequest http) {
		var userId = CurrentUser.id(authentication).orElseThrow(UserNotFoundException::new);
		var client = new ClientInfo(clientIps.resolve(http), http.getHeader(HttpHeaders.USER_AGENT));
		AuthService.Session session = passwordChanges.change(userId, request.currentPassword(), request.newPassword(), client);
		AccessToken token = session.accessToken();
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken()))
				.body(new AuthResponse(token.value(), AccessToken.TOKEN_TYPE, token.expiresIn()));
	}
}
