package com.abhiraj.archyra.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.abhiraj.archyra.ir.Language;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

	@TempDir
	Path workDir;

	@Mock
	GitCloner gitCloner;

	@Mock
	FileDiscovery fileDiscovery;

	@Test
	void clonesIntoTheWorkDirAndDiscoversFilesInTheClone() {
		DiscoveredFiles discovered = new DiscoveredFiles(
				List.of(new SourceFile("A.java", workDir.resolve("repo/A.java"), Language.JAVA)),
				Map.of(Language.JAVA, 1), 3);
		when(fileDiscovery.discover(any())).thenReturn(discovered);

		IngestionResult result = new IngestionService(gitCloner, fileDiscovery)
				.ingest("https://github.com/owner/my-repo", workDir);

		verify(gitCloner).clone("https://github.com/owner/my-repo", workDir.resolve("repo"));
		verify(fileDiscovery).discover(workDir.resolve("repo"));
		assertThat(result.repositoryName()).isEqualTo("my-repo");
		assertThat(result.root()).isEqualTo(workDir.resolve("repo"));
		assertThat(result.files()).isSameAs(discovered);
	}

	@Test
	void takesTheRepositoryNameFromTheUrl() {
		assertThat(IngestionService.repositoryName("https://github.com/owner/repo")).isEqualTo("repo");
		assertThat(IngestionService.repositoryName("https://github.com/owner/repo.git")).isEqualTo("repo");
		assertThat(IngestionService.repositoryName("https://github.com/owner/.github")).isEqualTo(".github");
	}
}
