package com.abhiraj.archyra.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.common.AnalysisFailureException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Shallow-clones a public GitHub repository with the {@code git} CLI.
 *
 * <p>The URL is validated again here, independently of request validation, because it is passed to
 * an external process. Credentials prompts are disabled so a private repository fails instead of
 * hanging.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitCloner {

	private static final String GITHUB_PREFIX = "https://github.com/";

	private final IngestionProperties properties;

	/**
	 * Clones {@code repositoryUrl} into {@code targetDir} (which must not exist yet).
	 *
	 * @throws AnalysisFailureException if the clone fails or times out
	 */
	public void clone(String repositoryUrl, Path targetDir) {
		if (repositoryUrl == null || !repositoryUrl.startsWith(GITHUB_PREFIX)
				|| repositoryUrl.contains("..") || repositoryUrl.startsWith("-")) {
			throw new AnalysisFailureException("Only https://github.com repositories can be analysed");
		}

		Path logFile = targetDir.resolveSibling("clone.log");
		List<String> command = List.of(
				"git", "clone",
				"--depth", "1",
				"--single-branch",
				"--no-tags",
				"--config", "core.symlinks=false",
				"--",
				repositoryUrl,
				targetDir.toString());

		ProcessBuilder builder = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(logFile.toFile());
		builder.environment().put("GIT_TERMINAL_PROMPT", "0");

		Process process;
		try {
			process = builder.start();
		}
		catch (IOException ex) {
			log.error("Could not start git", ex);
			throw new AnalysisFailureException("The repository could not be cloned: git is not available on the server", ex);
		}

		try {
			if (!process.waitFor(properties.cloneTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new AnalysisFailureException(
						"Cloning the repository took longer than " + properties.cloneTimeout().toSeconds() + " seconds");
			}
		}
		catch (InterruptedException ex) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new AnalysisFailureException("The analysis was interrupted while cloning", ex);
		}

		if (process.exitValue() != 0) {
			log.warn("git clone of {} failed with exit code {}: {}", repositoryUrl, process.exitValue(), tail(logFile));
			throw new AnalysisFailureException(
					"The repository could not be cloned. Check that it exists and is public.");
		}
	}

	private static String tail(Path file) {
		try {
			String content = Files.readString(file);
			return content.length() <= 500 ? content.strip() : content.substring(content.length() - 500).strip();
		}
		catch (IOException ex) {
			return "(no output)";
		}
	}
}
