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
	@Mock
	AnalysisRegistrar registrar;

	/** A restored NOT_ANALYZED entry is idle: no run owned it, so a restart must not fail it. */
	private static final List<AnalysisStatus> IDLE = List.of(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED, AnalysisStatus.NOT_ANALYZED);

	@Test
	void marksAnalysesThatWereInFlightAtShutdownAsFailed() {
		Analysis queued = Analysis.queued("https://github.com/o/a", java.util.UUID.randomUUID());
		Analysis parsing = Analysis.queued("https://github.com/o/b", java.util.UUID.randomUUID());
		parsing.advanceTo(AnalysisStatus.PARSING, 25);
		when(analysisRepository.findByStatusNotIn(IDLE))
				.thenReturn(List.of(queued, parsing));

		new AnalysisRecovery(analysisRepository, registrar).failInterruptedAnalyses();

		assertThat(queued.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(parsing.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(parsing.getFailureReason()).contains("server restart");
		verify(analysisRepository).saveAll(List.of(queued, parsing));
	}

	@Test
	void leavesFinishedAnalysesAlone() {
		when(analysisRepository.findByStatusNotIn(IDLE))
				.thenReturn(List.of());

		new AnalysisRecovery(analysisRepository, registrar).failInterruptedAnalyses();

		verify(analysisRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void returnsTheUnitOfEveryFailedAnalysisThatWasNeverRefundedAndKeepsGoingAfterAnError() {
		Analysis first = Analysis.queued("https://github.com/o/a", java.util.UUID.randomUUID());
		Analysis second = Analysis.queued("https://github.com/o/b", java.util.UUID.randomUUID());
		org.springframework.test.util.ReflectionTestUtils.setField(first, "id", java.util.UUID.randomUUID());
		org.springframework.test.util.ReflectionTestUtils.setField(second, "id", java.util.UUID.randomUUID());
		when(analysisRepository.findByStatusNotIn(IDLE)).thenReturn(List.of());
		when(analysisRepository.findByStatusAndQuotaChargedTrueAndQuotaRefundedFalse(AnalysisStatus.FAILED))
				.thenReturn(List.of(first, second));
		when(registrar.refundQuota(first.getId())).thenThrow(new IllegalStateException("db down"));

		new AnalysisRecovery(analysisRepository, registrar).failInterruptedAnalyses();

		verify(registrar).refundQuota(first.getId());
		verify(registrar).refundQuota(second.getId());
	}
}
