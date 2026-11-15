package com.abhiraj.archyra.ingestion;

import java.nio.file.Path;

/**
 * Output of the ingestion stage.
 *
 * @param repositoryName repository name taken from the URL ({@code repo} of {@code owner/repo})
 * @param root           where the repository was cloned; valid until the work directory is deleted
 */
public record IngestionResult(String repositoryName, Path root, DiscoveredFiles files) {
}
