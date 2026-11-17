package com.abhiraj.archyra.auth.oauth2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

class GitHubEmailClientTest {

	@RegisterExtension
	static final WireMockExtension GITHUB = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	private GitHubEmailClient client() {
		return new GitHubEmailClient(GITHUB.baseUrl() + "/user/emails");
	}

	private static void emails(String json) {
		GITHUB.stubFor(get(urlEqualTo("/user/emails")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(json)));
	}

	@Test
	void thePrimaryVerifiedAddressIsChosenAndTheTokenIsSent() {
		emails("""
				[{"email":"other@example.com","primary":false,"verified":true,"visibility":null},
				 {"email":"main@example.com","primary":true,"verified":true,"visibility":"public"}]""");

		assertThat(client().primaryVerifiedEmail("gh-token")).contains("main@example.com");

		GITHUB.verify(getRequestedFor(urlEqualTo("/user/emails"))
				.withHeader("Authorization", equalTo("Bearer gh-token"))
				.withHeader("Accept", equalTo("application/vnd.github+json")));
	}

	@Test
	void aPrimaryAddressThatIsNotVerifiedIsNotAccepted() {
		emails("""
				[{"email":"main@example.com","primary":true,"verified":false},
				 {"email":"other@example.com","primary":false,"verified":true}]""");

		assertThat(client().primaryVerifiedEmail("gh-token")).isEmpty();
	}

	@Test
	void anEmptyListMeansNoEmail() {
		emails("[]");

		assertThat(client().primaryVerifiedEmail("gh-token")).isEmpty();
	}

	@Test
	void anErrorFromGitHubIsAFailureNotAnEmptyAnswer() {
		GITHUB.stubFor(get(urlEqualTo("/user/emails")).willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> client().primaryVerifiedEmail("gh-token")).isInstanceOfSatisfying(SocialLoginException.class,
				ex -> assertThat(ex.code()).isEqualTo(SocialLoginErrors.FAILED));
	}

	@Test
	void anUnreachableServerIsAFailure() {
		assertThatThrownBy(() -> new GitHubEmailClient("http://localhost:1/user/emails").primaryVerifiedEmail("gh-token"))
				.isInstanceOf(SocialLoginException.class);
	}
}
