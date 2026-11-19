package com.abhiraj.archyra.auth;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.auth.oauth2.LinkIntentService;

import lombok.RequiredArgsConstructor;

/**
 * Removes everything the authentication feature holds for one user when the account is deleted: refresh tokens (every
 * session), one-time tokens (including a pending deletion confirmation) and OAuth link intents. The public door of
 * {@code auth/} for {@code user/AccountDeletionService}, which must not reach the package-private repositories.
 */
@Service
@RequiredArgsConstructor
public class AccountDataPurger {

	private final RefreshTokenRepository refreshTokens;
	private final OneTimeTokenRepository oneTimeTokens;
	private final LinkIntentService linkIntents;

	@Transactional(propagation = Propagation.MANDATORY)
	public void deleteAllOf(UUID userId) {
		refreshTokens.deleteByUserId(userId);
		oneTimeTokens.deleteByUserId(userId);
		linkIntents.deleteAllOf(userId);
	}
}
