package com.abhiraj.archyra.mail;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Remembers the emails a flow asked for, so tests can read the link without an SMTP server. */
public class RecordingEmailService implements EmailService {

	/**
	 * @param kind      {@code VERIFY}, {@code RESET}, {@code ACCOUNT_EXISTS}, {@code IDENTITY_LINKED}, {@code IDENTITY_UNLINKED}, {@code ACCOUNT_DELETE} (the confirmation link) or {@code ACCOUNT_DELETED}
	 * @param link      the tokened link, or for {@code ACCOUNT_EXISTS} the sign-in page (no token)
	 * @param resetLink only for {@code ACCOUNT_EXISTS}: the forgot-password page
	 * @param providers only for {@code ACCOUNT_EXISTS}: the social logins named in the notice; for the identity notices the
	 *                  one provider they are about, and {@code link} is the forgot-password page (no token)
	 */
	public record Sent(String kind, String to, String name, String link, String resetLink, List<String> providers) {

		public Sent(String kind, String to, String name, String link) {
			this(kind, to, name, link, null, List.of());
		}

		/** The one-time token in the link. */
		public String token() {
			Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(link);
			if (!matcher.find()) {
				throw new IllegalStateException("No token in " + link);
			}
			return matcher.group(1);
		}
	}

	private final List<Sent> sent = new CopyOnWriteArrayList<>();
	private volatile boolean failIdentityNotices;

	/** Makes the identity notices throw, to prove a mail failure never fails the link or unlink that triggered it. */
	public void failIdentityNotices(boolean fail) {
		this.failIdentityNotices = fail;
	}

	@Override
	public void sendVerificationEmail(String to, String name, String link) {
		sent.add(new Sent("VERIFY", to, name, link));
	}

	@Override
	public void sendPasswordResetEmail(String to, String name, String link) {
		sent.add(new Sent("RESET", to, name, link));
	}

	@Override
	public void sendAccountExistsNotice(String to, String name, List<String> providers, String loginLink, String resetLink) {
		sent.add(new Sent("ACCOUNT_EXISTS", to, name, loginLink, resetLink, providers));
	}

	@Override
	public void sendIdentityLinkedNotice(String to, String name, String provider, Instant at, String resetLink) {
		identityNotice("IDENTITY_LINKED", to, name, provider, resetLink);
	}

	@Override
	public void sendIdentityUnlinkedNotice(String to, String name, String provider, Instant at, String resetLink) {
		identityNotice("IDENTITY_UNLINKED", to, name, provider, resetLink);
	}

	@Override
	public void sendPasswordChangedNotice(String to, String name, Instant at, String resetLink) {
		if (failIdentityNotices) {
			throw new IllegalStateException("simulated mail failure");
		}
		sent.add(new Sent("PASSWORD_CHANGED", to, name, resetLink, resetLink, List.of()));
	}

	@Override
	public void sendAccountDeletionRequest(String to, String name, String link) {
		sent.add(new Sent("ACCOUNT_DELETE", to, name, link));
	}

	@Override
	public void sendAccountDeletedNotice(String to, String name, Instant at) {
		if (failIdentityNotices) {
			throw new IllegalStateException("simulated mail failure");
		}
		sent.add(new Sent("ACCOUNT_DELETED", to, name, ""));
	}

	/** The billing emails, with what they said ({@code mail} is one of the {@link BillingMails} records). */
	public record BillingSent(String kind, String to, Object mail) {
	}

	private final List<BillingSent> billing = new CopyOnWriteArrayList<>();

	@Override
	public void sendPremiumActivated(String to, String name, BillingMails.PremiumActivated mail) {
		billing.add(new BillingSent("PREMIUM_ACTIVATED", to, mail));
	}

	@Override
	public void sendPremiumReminder(String to, String name, BillingMails.PremiumEnding mail) {
		billing.add(new BillingSent("PREMIUM_REMINDER", to, mail));
	}

	@Override
	public void sendPremiumExpired(String to, String name, BillingMails.PremiumEnding mail) {
		billing.add(new BillingSent("PREMIUM_EXPIRED", to, mail));
	}

	@Override
	public void sendRepositoriesDeleted(String to, String name, BillingMails.RepositoriesDeleted mail) {
		billing.add(new BillingSent("REPOSITORIES_DELETED", to, mail));
	}

	/** The billing emails sent to an address, oldest first. */
	public List<BillingSent> billingTo(String address) {
		return billing.stream().filter(mail -> mail.to().equals(address)).toList();
	}

	public List<BillingSent> billingTo(String address, String kind) {
		return billingTo(address).stream().filter(mail -> mail.kind().equals(kind)).toList();
	}

	private void identityNotice(String kind, String to, String name, String provider, String resetLink) {
		if (failIdentityNotices) {
			throw new IllegalStateException("simulated mail failure");
		}
		sent.add(new Sent(kind, to, name, resetLink, resetLink, List.of(provider)));
	}

	public List<Sent> sentTo(String address) {
		return sent.stream().filter(mail -> mail.to().equals(address)).toList();
	}

	public Sent lastTo(String address) {
		List<Sent> mails = sentTo(address);
		return mails.get(mails.size() - 1);
	}

	public int total() {
		return sent.size();
	}
}
