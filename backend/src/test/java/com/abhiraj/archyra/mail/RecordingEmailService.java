package com.abhiraj.archyra.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Remembers the emails a flow asked for, so tests can read the link without an SMTP server. */
public class RecordingEmailService implements EmailService {

	public record Sent(String kind, String to, String name, String link) {

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

	@Override
	public void sendVerificationEmail(String to, String name, String link) {
		sent.add(new Sent("VERIFY", to, name, link));
	}

	@Override
	public void sendPasswordResetEmail(String to, String name, String link) {
		sent.add(new Sent("RESET", to, name, link));
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
