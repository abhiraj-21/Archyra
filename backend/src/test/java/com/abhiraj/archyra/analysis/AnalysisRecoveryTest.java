package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalysisRecoveryTest {

	@Mock
	AnalysisRepository analysisRepository;

	@Test
	void marksAnalysesThatWereInFlightAtShutdownAsFailed() {
		Analysis queued = Analysis.queued("https://github.com/o/a", java.util.UUID.randomUUID());
		Analysis parsing = Analysis.queued("https://github.com/o/b", java.util.UUID.randomUUID());
		parsing.advanceTo(AnalysisStatus.PARSING, 25);
		when(analysisRepository.findByStatusNotIn(List.of(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED)))
				.thenReturn(List.of(queued, parsing));

		new AnalysisRecovery(analysisRepository).failInterruptedAnalyses();

		assertThat(queued.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(parsing.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(parsing.getFailureReason()).contains("server restart");
		verify(analysisRepository).saveAll(List.of(queued, parsing));
	}

	@Test
	void leavesFinishedAnalysesAlone() {
		when(analysisRepository.findByStatusNotIn(List.of(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED)))
				.thenReturn(List.of());

		new AnalysisRecovery(analysisRepository).failInterruptedAnalyses();

		verify(analysisRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}
}
