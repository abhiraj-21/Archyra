package com.abhiraj.archyra.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.auth.AuthDtos.ConfirmAccountDeletionRequest;
import com.abhiraj.archyra.auth.AuthDtos.DeleteAccountRequest;
import com.abhiraj.archyra.auth.AuthDtos.MessageResponse;
import com.abhiraj.archyra.common.UserNotFoundException;
import com.abhiraj.archyra.security.ClientIpResolver;
import com.abhiraj.archyra.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Deleting the signed-in user's account (Settings → Danger zone), bearer-authenticated like the rest of
 * {@code /users/me}. Success answers {@code 204} and clears the refresh cookie; every session of the user is gone.
 * Rate limited per client IP with the login and the sensitive-email buckets ({@code RateLimitFilter}).
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class AccountDeletionController {

	private final AccountDeletionFlows flows;
	private final RefreshCookies cookies;
	private final ClientIpResolver clientIps;

	/** An account with a password: delete it, proven by the current password. */
	@PostMapping("/delete")
	public ResponseEntity<Void> delete(Authentication authentication, @Valid @RequestBody DeleteAccountRequest request,
			HttpServletRequest http) {
		flows.deleteWithPassword(CurrentUser.id(authentication).orElseThrow(UserNotFoundException::new), request.password(), client(http));
		return deleted();
	}

	/** An account without a password: email a confirmation link. */
	@PostMapping("/delete-request")
	public ResponseEntity<MessageResponse> request(Authentication authentication) {
		flows.requestLink(CurrentUser.id(authentication).orElseThrow(UserNotFoundException::new));
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MessageResponse("We emailed you a confirmation link."));
	}

	/** An account without a password: delete it with the token from the link. */
	@PostMapping("/delete-confirm")
	public ResponseEntity<Void> confirm(Authentication authentication, @Valid @RequestBody ConfirmAccountDeletionRequest request,
			HttpServletRequest http) {
		flows.confirm(CurrentUser.id(authentication).orElseThrow(UserNotFoundException::new), request.token(), client(http));
		return deleted();
	}

	private ResponseEntity<Void> deleted() {
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
	}

	private ClientInfo client(HttpServletRequest http) {
		return new ClientInfo(clientIps.resolve(http), http.getHeader(HttpHeaders.USER_AGENT));
	}
}
