package com.abhiraj.archyra.ingestion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits applied while ingesting a repository ({@code archyra.ingestion.*}).
 *
 * @param maxFiles           maximum number of files in the repository (after skipping ignored directories)
 * @param maxRepositoryBytes maximum total size of those files
 * @param maxSourceFileBytes source files larger than this are skipped (generated or minified code)
 * @param cloneTimeout       maximum time for {@code git clone}
 */
@ConfigurationProperties(prefix = "archyra.ingestion")
public record IngestionProperties(
		@DefaultValue("10000") int maxFiles,
		@DefaultValue("209715200") long maxRepositoryBytes,
		@DefaultValue("1048576") long maxSourceFileBytes,
		@DefaultValue("2m") Duration cloneTimeout) {
}
