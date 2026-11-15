package com.abhiraj.archyra.ingestion;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Ingestion stage: clone the repository, then discover and classify its files. */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

	private final GitCloner gitCloner;
	private final FileDiscovery fileDiscovery;

	/**
	 * Clones the repository into {@code workDir/repo} and discovers its files. The caller owns
	 * {@code workDir} and is responsible for deleting it.
	 */
	public IngestionResult ingest(String repositoryUrl, Path workDir) {
		Path cloneDir = workDir.resolve("repo");
		gitCloner.clone(repositoryUrl, cloneDir);
		DiscoveredFiles files = fileDiscovery.discover(cloneDir);
		log.info("Discovered {} files ({} source files: {}) in {}",
				files.totalFiles(), files.sourceFiles().size(), files.languageFiles(), repositoryUrl);
		return new IngestionResult(repositoryName(repositoryUrl), cloneDir, files);
	}

	static String repositoryName(String repositoryUrl) {
		String name = repositoryUrl.substring(repositoryUrl.lastIndexOf('/') + 1);
		return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
	}
}
