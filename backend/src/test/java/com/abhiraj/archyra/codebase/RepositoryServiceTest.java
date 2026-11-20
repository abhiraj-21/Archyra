package com.abhiraj.archyra.codebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.abhiraj.archyra.analysis.AnalysisMetadataService;
import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.analysis.LanguageStat;
import com.abhiraj.archyra.common.AnalysisNotCompletedException;
import com.abhiraj.archyra.graph.GraphCounts;
import com.abhiraj.archyra.graph.GraphQueryService;

@ExtendWith(MockitoExtension.class)
class RepositoryServiceTest {

	private static final UUID ID = UUID.randomUUID();

	@Mock
	private AnalysisService analysisService;
	@Mock
	private AnalysisMetadataService metadataService;
	@Mock
	private GraphQueryService graphQueryService;
	@InjectMocks
	private RepositoryService service;

	@Test
	void combinesLanguagesFromMetadataWithNameAndCountsFromTheGraph() {
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(metadataService.getLanguages(ID)).thenReturn(List.of(new LanguageStat("Java", 140), new LanguageStat("SQL", 3)));
		when(metadataService.getTechnologies(ID)).thenReturn(List.of(
				new AnalysisMetadataService.TechnologyStat("Spring Boot", "BACKEND_FRAMEWORK"),
				new AnalysisMetadataService.TechnologyStat("Maven", "BUILD_TOOL")));
		when(graphQueryService.getCounts(ID.toString())).thenReturn(Optional.of(new GraphCounts("payment-service", 87, 412)));

		assertThat(service.getRepository(ID.toString()))
				.isEqualTo(new RepositoryResponse("payment-service", List.of("Java", "SQL"), 143, 87, 412,
						List.of(new TechnologyResponse("Spring Boot", "BACKEND_FRAMEWORK"), new TechnologyResponse("Maven", "BUILD_TOOL"))));
	}

	@Test
	void noDetectedTechnologiesGiveAnEmptyListNeverNull() {
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(metadataService.getLanguages(ID)).thenReturn(List.of(new LanguageStat("Java", 1)));
		when(metadataService.getTechnologies(ID)).thenReturn(List.of());
		when(graphQueryService.getCounts(ID.toString())).thenReturn(Optional.of(new GraphCounts("tiny", 1, 1)));

		assertThat(service.getRepository(ID.toString()).technologies()).isNotNull().isEmpty();
	}

	@Test
	void anAnalysisThatIsNotCompletedNeverTouchesTheStores() {
		when(analysisService.requireCompleted("x")).thenThrow(new AnalysisNotCompletedException("not yet"));

		assertThatThrownBy(() -> service.getRepository("x")).isInstanceOf(AnalysisNotCompletedException.class);
		verifyNoInteractions(metadataService, graphQueryService);
	}

	@Test
	void aCompletedAnalysisWithoutAGraphIsAServerError() {
		when(analysisService.requireCompleted(ID.toString())).thenReturn(ID);
		when(metadataService.getLanguages(ID)).thenReturn(List.of());
		when(graphQueryService.getCounts(ID.toString())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getRepository(ID.toString())).isInstanceOf(IllegalStateException.class);
	}
}
