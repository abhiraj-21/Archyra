package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.common.ServiceBusyException;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

	private static final String URL = "https://github.com/owner/repo";

	@Mock
	private AnalysisRepository analysisRepository;

	@Mock
	private AnalysisPipeline analysisPipeline;

	private AnalysisService service;

	@BeforeEach
	void setUp() {
		service = new AnalysisService(analysisRepository, analysisPipeline);
		// Simulate the persistence layer assigning a generated id on save.
		when(analysisRepository.save(any(Analysis.class))).thenAnswer(invocation -> {
			Analysis analysis = invocation.getArgument(0);
			if (analysis.getId() == null) {
				ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
			}
			return analysis;
		});
	}

	@Test
	void persistsANewQueuedAnalysisForTheRequestedRepository() {
		service.createAnalysis(new CreateAnalysisRequest(URL));

		ArgumentCaptor<Analysis> saved = ArgumentCaptor.forClass(Analysis.class);
		verify(analysisRepository).save(saved.capture());
		assertThat(saved.getValue().getRepositoryUrl()).isEqualTo(URL);
		assertThat(saved.getValue().getStatus()).isEqualTo(AnalysisStatus.QUEUED);
		assertThat(saved.getValue().getProgress()).isZero();
	}

	@Test
	void responseCarriesThePersistedIdAndQueuedStatus() {
		CreateAnalysisResponse response = service.createAnalysis(new CreateAnalysisRequest(URL));

		ArgumentCaptor<Analysis> saved = ArgumentCaptor.forClass(Analysis.class);
		verify(analysisRepository).save(saved.capture());
		assertThat(response.analysisId()).isEqualTo(saved.getValue().getId().toString());
		assertThat(response.status()).isEqualTo(AnalysisStatus.QUEUED);
	}

	@Test
	void eachAnalysisGetsADistinctUuid() {
		CreateAnalysisResponse first = service.createAnalysis(new CreateAnalysisRequest(URL));
		CreateAnalysisResponse second = service.createAnalysis(new CreateAnalysisRequest(URL));

		assertThat(UUID.fromString(first.analysisId())).isNotNull();
		assertThat(first.analysisId()).isNotEqualTo(second.analysisId());
	}

	@Test
	void startsThePipelineOnlyAfterTheAnalysisIsSaved() {
		CreateAnalysisResponse response = service.createAnalysis(new CreateAnalysisRequest(URL));

		InOrder order = inOrder(analysisRepository, analysisPipeline);
		order.verify(analysisRepository).save(any(Analysis.class));
		order.verify(analysisPipeline).run(UUID.fromString(response.analysisId()));
	}

	@Test
	void marksTheAnalysisFailedAndSignalsBusyWhenTheQueueIsFull() {
		doThrow(new TaskRejectedException("queue full")).when(analysisPipeline).run(any());

		assertThatThrownBy(() -> service.createAnalysis(new CreateAnalysisRequest(URL)))
				.isInstanceOf(ServiceBusyException.class);

		ArgumentCaptor<Analysis> saved = ArgumentCaptor.forClass(Analysis.class);
		verify(analysisRepository, org.mockito.Mockito.times(2)).save(saved.capture());
		Analysis analysis = saved.getAllValues().getLast();
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).contains("queue is full");
	}
}
