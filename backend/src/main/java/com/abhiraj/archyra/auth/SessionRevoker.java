package com.abhiraj.archyra.auth;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.abhiraj.archyra.user.UserDisabledEvent;

import lombok.RequiredArgsConstructor;

/** Ends every refresh-token family of a user when an administrator disables the account. */
@Component
@RequiredArgsConstructor
class SessionRevoker {

	private final RefreshTokenService refreshTokens;

	/** Runs inside the disabling transaction, so the account and its sessions change together. */
	@EventListener
	void onUserDisabled(UserDisabledEvent event) {
		refreshTokens.revokeAllForUser(event.userId());
	}
}
