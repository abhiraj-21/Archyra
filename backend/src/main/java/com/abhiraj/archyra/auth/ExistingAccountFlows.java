package com.abhiraj.archyra.auth;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.mail.EmailService;
import com.abhiraj.archyra.security.SecurityAuditLogger;
import com.abhiraj.archyra.security.SecurityProperties;
import com.abhiraj.archyra.user.Provider;
import com.abhiraj.archyra.user.User;
import com.abhiraj.archyra.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What happens when someone registers an address that already has an account. The caller has already answered with
 * the same {@code 202} it gives a new address, and all of this runs afterwards on the bounded mail executor, so neither
 * the response nor its timing tells the two cases apart. Nothing here is visible to the requester.
 *
 * <ul>
 * <li><b>Disabled account:</b> nothing is sent and nothing changes.</li>
 * <li><b>Unverified account:</b> a retry of the signup. A fresh verification link goes out (older ones stop working).
 * The stored name and password are <i>not</i> replaced: whoever re-registers an unverified address has not proven they
 * own it either, and replacing the password would let them put their own on the account the real owner then verifies.</li>
 * <li><b>Verified account:</b> a notice to the owner, at most one per address per hour, naming the social login(s) when
 * the account has no password. The links carry no token.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class ExistingAccountFlows {

	private final UserService userService;
	private final AccountEmailFlows emailFlows;
	private final EmailService emailService;
	private final RegisterNoticeThrottle throttle;
	private final SecurityProperties properties;
	private final SecurityAuditLogger audit;

	/** @param normalizedEmail trimmed and lower-cased, as stored */
	@Async(EmailService.MAIL_EXECUTOR)
	void handle(String normalizedEmail, String ip) {
		try {
			userService.findByEmail(normalizedEmail).ifPresent(user -> handle(user, ip));
		}
		catch (RuntimeException ex) {
			// Never rethrow and never log the address: nothing may depend on, or reveal, the outcome.
			log.warn("Handling a registration of an existing account failed ({})", ex.getClass().getSimpleName());
			log.debug("Detail", ex);
		}
	}

	private void handle(User user, String ip) {
		if (!user.isEnabled()) {
			audit.eventWithCase("REGISTER_EXISTING_ACCOUNT", user.getId(), ip, "DISABLED");
			return;
		}
		if (!user.isEmailVerified()) {
			emailFlows.sendVerificationEmail(user);
			audit.eventWithCase("REGISTER_EXISTING_ACCOUNT", user.getId(), ip, "UNVERIFIED_RETRY");
			return;
		}
		if (!throttle.tryAcquire(user.getEmail())) {
			audit.eventWithCase("REGISTER_EXISTING_ACCOUNT", user.getId(), ip, "NOTICE_THROTTLED");
			return;
		}
		List<Provider> providers = userService.linkedProviders(user.getId());
		boolean socialOnly = !user.hasPassword() && !providers.isEmpty();
		String base = properties.frontendBaseUrl();
		String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		emailService.sendAccountExistsNotice(user.getEmail(), user.getName(),
				socialOnly ? providers.stream().map(ExistingAccountFlows::displayName).toList() : List.of(),
				root + "/login", root + "/forgot-password");
		audit.eventWithCase("REGISTER_EXISTING_ACCOUNT", user.getId(), ip, socialOnly ? "NOTICE_SOCIAL_ONLY" : "NOTICE_PASSWORD");
	}

	private static String displayName(Provider provider) {
		return switch (provider) {
			case GOOGLE -> "Google";
			case GITHUB -> "GitHub";
		};
	}
}
