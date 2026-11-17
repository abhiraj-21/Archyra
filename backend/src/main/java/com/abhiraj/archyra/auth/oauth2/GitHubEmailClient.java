package com.abhiraj.archyra.auth.oauth2;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads the signed-in GitHub user's email addresses. The public profile {@code email} is never used: it is not
 * guaranteed to be verified. Only the address GitHub marks both {@code primary} and {@code verified} counts.
 */
@Slf4j
@Component
class GitHubEmailClient {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GitHubEmail(String email, boolean primary, boolean verified) {
	}

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final RestClient restClient;
	private final String emailsUri;

	GitHubEmailClient(@Value("${app.github.emails-uri:https://api.github.com/user/emails}") String emailsUri) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(TIMEOUT);
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
		this.emailsUri = emailsUri;
	}

	/**
	 * @return the primary verified address, or empty if there is none
	 * @throws SocialLoginException if GitHub cannot be asked or answers with an error
	 */
	Optional<String> primaryVerifiedEmail(String accessToken) {
		try {
			List<GitHubEmail> emails = restClient.get().uri(emailsUri)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
					.header(HttpHeaders.ACCEPT, "application/vnd.github+json")
					.retrieve()
					.body(new ParameterizedTypeReference<List<GitHubEmail>>() {
					});
			if (emails == null) {
				return Optional.empty();
			}
			return emails.stream().filter(e -> e.primary() && e.verified() && e.email() != null && !e.email().isBlank())
					.map(GitHubEmail::email).findFirst();
		}
		catch (RuntimeException ex) {
			log.warn("Could not read the GitHub email addresses ({})", ex.getClass().getSimpleName());
			throw new SocialLoginException(SocialLoginErrors.FAILED, "GitHub emails unavailable");
		}
	}
}
