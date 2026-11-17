package com.abhiraj.archyra.mail;

/**
 * Outgoing email. Implementations must not block the caller and must never throw to it: whether a message could
 * be sent is not allowed to change a response, because that would reveal whether an address is registered.
 *
 * <p>The links contain one-time secrets. Never log them above DEBUG.
 */
public interface EmailService {

	void sendVerificationEmail(String to, String name, String link);

	void sendPasswordResetEmail(String to, String name, String link);
}
