package com.abhiraj.archyra.mail;

import java.time.Instant;
import java.util.List;

/**
 * Outgoing email. Implementations must not block the caller and must never throw to it: whether a message could
 * be sent is not allowed to change a response, because that would reveal whether an address is registered.
 *
 * <p>The links contain one-time secrets. Never log them above DEBUG.
 */
public interface EmailService {

	/** Bean name of the bounded pool mail is sent on; other features use it to do mail-related work off the request thread. */
	String MAIL_EXECUTOR = "mailExecutor";

	void sendVerificationEmail(String to, String name, String link);

	void sendPasswordResetEmail(String to, String name, String link);

	/**
	 * Tells the owner of an address that someone tried to register it. The links carry no token.
	 *
	 * @param providers display names of the social logins the account uses when it has no password ({@code Google},
	 *                  {@code GitHub}); empty for a password account
	 * @param loginLink the sign-in page
	 * @param resetLink the forgot-password page
	 */
	void sendAccountExistsNotice(String to, String name, List<String> providers, String loginLink, String resetLink);

	/**
	 * Tells the owner that a social login was linked to their account. The link carries no token.
	 *
	 * @param provider  display name ({@code Google}, {@code GitHub})
	 * @param at        when it happened
	 * @param resetLink the forgot-password page, for "this wasn't me"
	 */
	void sendIdentityLinkedNotice(String to, String name, String provider, Instant at, String resetLink);

	/** Tells the owner that a social login was removed from their account. Same shape and rules as the linked notice. */
	void sendIdentityUnlinkedNotice(String to, String name, String provider, Instant at, String resetLink);

	/**
	 * Tells the owner that the password was changed from Settings and that other devices were signed out. The link
	 * carries no token: it is the forgot-password page, for "this wasn't me".
	 */
	void sendPasswordChangedNotice(String to, String name, Instant at, String resetLink);

	/**
	 * Sends the confirmation link for deleting an account that has no password (it signs in with Google or GitHub, so
	 * there is no password to ask for). The link carries a one-time token that is valid for 15 minutes.
	 */
	void sendAccountDeletionRequest(String to, String name, String link);

	/**
	 * Tells the owner that the account was deleted, to the address held before the deletion. No link and no token. It does
	 * not mention the usage ledger.
	 */
	void sendAccountDeletedNotice(String to, String name, Instant at);
}
