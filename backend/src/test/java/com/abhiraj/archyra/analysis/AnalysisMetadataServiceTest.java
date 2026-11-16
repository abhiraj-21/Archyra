package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.abhiraj.archyra.ir.Language;

@ExtendWith(MockitoExtension.class)
class AnalysisMetadataServiceTest {

	private static final UUID ID = UUID.randomUUID();

	@Mock
	private AnalysisMetadataRepository metadataRepository;
	@InjectMocks
	private AnalysisMetadataService service;

	@Test
	void languagesComeMostUsedFirstWithTiesByNameAndIncludeCountedOnlyOnes() {
		when(metadataRepository.findByAnalysisIdAndKind(ID, MetadataKind.LANGUAGE)).thenReturn(List.of(
				AnalysisMetadata.language(ID, Language.SQL, 3, false),
				AnalysisMetadata.language(ID, Language.PYTHON, 3, true),
				AnalysisMetadata.language(ID, Language.JAVA, 140, true)));

		assertThat(service.getLanguages(ID)).containsExactly(
				new LanguageStat("Java", 140), new LanguageStat("Python", 3), new LanguageStat("SQL", 3));
	}

	@Test
	void noLanguagesGivesAnEmptyList() {
		when(metadataRepository.findByAnalysisIdAndKind(ID, MetadataKind.LANGUAGE)).thenReturn(List.of());

		assertThat(service.getLanguages(ID)).isEmpty();
	}
}
