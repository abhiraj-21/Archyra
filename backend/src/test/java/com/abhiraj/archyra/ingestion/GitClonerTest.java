package com.abhiraj.archyra.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.abhiraj.archyra.common.AnalysisFailureException;

class GitClonerTest {

	@TempDir
	Path workDir;

	private final GitCloner cloner = new GitCloner(
			new IngestionProperties(10_000, 200_000_000, 1_000_000, Duration.ofSeconds(60)));

	@ParameterizedTest
	@ValueSource(strings = {
			"file:///etc/passwd",
			"http://github.com/owner/repo",
			"https://gitlab.com/owner/repo",
			"git@github.com:owner/repo.git",
			"--upload-pack=touch /tmp/pwned",
			"https://github.com/../etc/passwd",
			"/local/path"
	})
	void refusesAnythingButGitHubHttpsUrlsWithoutRunningGit(String url) {
		assertThatThrownBy(() -> cloner.clone(url, workDir.resolve("repo")))
				.isInstanceOf(AnalysisFailureException.class)
				.hasMessageContaining("github.com");
		assertThat(workDir.resolve("repo")).doesNotExist();
		assertThat(workDir.resolve("clone.log")).doesNotExist();
	}

	@Test
	void refusesNullUrl() {
		assertThatThrownBy(() -> cloner.clone(null, workDir.resolve("repo")))
				.isInstanceOf(AnalysisFailureException.class);
	}

	/** Needs network access and git: {@code ARCHYRA_NETWORK_TESTS=true ./mvnw test -Dtest=GitClonerTest}. */
	@Test
	@EnabledIfEnvironmentVariable(named = "ARCHYRA_NETWORK_TESTS", matches = "true")
	void shallowClonesAPublicRepository() throws Exception {
		Path target = workDir.resolve("repo");

		cloner.clone("https://github.com/octocat/Hello-World", target);

		assertThat(target.resolve(".git")).isDirectory();
		try (var files = Files.list(target)) {
			assertThat(files.count()).isGreaterThan(1);
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "ARCHYRA_NETWORK_TESTS", matches = "true")
	void reportsAMissingRepositoryWithoutHangingOnCredentialPrompts() {
		assertThatThrownBy(() -> cloner.clone(
				"https://github.com/archyra-test-nonexistent/definitely-not-a-repo-12345", workDir.resolve("repo")))
				.isInstanceOf(AnalysisFailureException.class)
				.hasMessageContaining("could not be cloned");
	}
}
