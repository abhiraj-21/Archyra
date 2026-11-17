package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.graph.GraphBuilder;

@ExtendWith(MockitoExtension.class)
class AnalysisCleanupTest {

	@Mock
	private AnalysisRepository analysisRepository;
	@Mock
	private AnalysisMetadataRepository metadataRepository;
	@Mock
	private GraphBuilder graphBuilder;
	@InjectMocks
	private AnalysisCleanup cleanup;

	private static Analysis analysis() {
		Analysis analysis = Analysis.queued("https://github.com/o/r", UUID.randomUUID());
		ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
		return analysis;
	}

	@Test
	void deletesTheGraphThenMetadataThenTheRow() {
		Analysis analysis = analysis();

		cleanup.delete(analysis);

		var order = inOrder(graphBuilder, metadataRepository, analysisRepository);
		order.verify(graphBuilder).delete(analysis.getId().toString());
		order.verify(metadataRepository).deleteByAnalysisId(analysis.getId());
		order.verify(analysisRepository).delete(analysis);
	}

	@Test
	void keepsTheRowWhenTheGraphCannotBeDeleted() {
		Analysis analysis = analysis();
		doThrow(new IllegalStateException("neo4j down")).when(graphBuilder).delete(analysis.getId().toString());

		assertThatThrownBy(() -> cleanup.delete(analysis)).isInstanceOf(IllegalStateException.class);

		verify(analysisRepository, never()).delete(analysis);
		verifyNoInteractions(metadataRepository);
	}
}
