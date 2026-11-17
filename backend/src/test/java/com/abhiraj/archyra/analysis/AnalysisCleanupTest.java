package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
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
	@Mock
	private AnalysisRegistrar registrar;
	@InjectMocks
	private AnalysisCleanup cleanup;

	private static Analysis analysis() {
		Analysis analysis = Analysis.queued("https://github.com/o/r", UUID.randomUUID());
		ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
		return analysis;
	}

	@Test
	void deletesTheGraphThenMetadataThenTheRowWithItsCredit() {
		Analysis analysis = analysis();

		cleanup.delete(analysis);

		var order = inOrder(graphBuilder, metadataRepository, registrar);
		order.verify(graphBuilder).delete(analysis.getId().toString());
		order.verify(metadataRepository).deleteByAnalysisId(analysis.getId());
		order.verify(registrar).deleteAndCredit(analysis);
	}

	@Test
	void keepsTheRowWhenTheGraphCannotBeDeleted() {
		Analysis analysis = analysis();
		doThrow(new IllegalStateException("neo4j down")).when(graphBuilder).delete(analysis.getId().toString());

		assertThatThrownBy(() -> cleanup.delete(analysis)).isInstanceOf(IllegalStateException.class);

		verify(registrar, never()).deleteAndCredit(analysis);
		verifyNoInteractions(metadataRepository);
	}

	// ---- deleteReplaced ------------------------------------------------------------------------

	private static Analysis completedRerun(Analysis replaced) {
		Analysis rerun = Analysis.queued("https://github.com/o/r", replaced == null ? UUID.randomUUID() : replaced.getOwnerId());
		ReflectionTestUtils.setField(rerun, "id", UUID.randomUUID());
		rerun.replaces(replaced == null ? null : replaced.getId());
		rerun.markCompleted();
		return rerun;
	}

	private static Analysis finished(AnalysisStatus status) {
		Analysis analysis = analysis();
		if (status == AnalysisStatus.COMPLETED) {
			analysis.markCompleted();
		}
		else {
			analysis.markFailed("old");
		}
		return analysis;
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.EnumSource(value = AnalysisStatus.class, names = { "COMPLETED", "FAILED" })
	void aCompletedRerunDeletesTheFinishedAnalysisItReplaces(AnalysisStatus status) {
		Analysis old = finished(status);
		Analysis rerun = completedRerun(old);
		given(analysisRepository.findById(old.getId())).willReturn(Optional.of(old));

		cleanup.deleteReplaced(rerun);

		var order = inOrder(graphBuilder, metadataRepository, registrar);
		order.verify(graphBuilder).delete(old.getId().toString());
		order.verify(metadataRepository).deleteByAnalysisId(old.getId());
		order.verify(registrar).deleteAndCredit(old);
	}

	@Test
	void anAnalysisThatReplacesNothingDeletesNothing() {
		cleanup.deleteReplaced(completedRerun(null));

		verifyNoInteractions(analysisRepository, graphBuilder, metadataRepository);
	}

	@Test
	void anotherUsersAnalysisIsNeverDeleted() {
		Analysis old = finished(AnalysisStatus.COMPLETED);
		Analysis rerun = Analysis.queued("https://github.com/o/r", UUID.randomUUID()); // a different owner
		ReflectionTestUtils.setField(rerun, "id", UUID.randomUUID());
		rerun.replaces(old.getId());
		given(analysisRepository.findById(old.getId())).willReturn(Optional.of(old));

		cleanup.deleteReplaced(rerun);

		verifyNoInteractions(graphBuilder, metadataRepository, registrar);
	}

	@Test
	void anAnalysisThatIsStillRunningIsNeverDeleted() {
		Analysis running = analysis();
		Analysis rerun = completedRerun(running);
		given(analysisRepository.findById(running.getId())).willReturn(Optional.of(running));

		cleanup.deleteReplaced(rerun);

		verifyNoInteractions(graphBuilder, metadataRepository);
	}

	@Test
	void anAnalysisThatIsAlreadyGoneIsFine() {
		Analysis old = finished(AnalysisStatus.COMPLETED);
		given(analysisRepository.findById(old.getId())).willReturn(Optional.empty());

		assertThatCode(() -> cleanup.deleteReplaced(completedRerun(old))).doesNotThrowAnyException();
		verifyNoInteractions(graphBuilder);
	}

	@Test
	void aFailureToDeleteTheOldOneNeverEscapes() {
		Analysis old = finished(AnalysisStatus.COMPLETED);
		given(analysisRepository.findById(old.getId())).willReturn(Optional.of(old));
		doThrow(new IllegalStateException("neo4j down")).when(graphBuilder).delete(old.getId().toString());

		assertThatCode(() -> cleanup.deleteReplaced(completedRerun(old))).doesNotThrowAnyException();
		verify(registrar, never()).deleteAndCredit(old);
	}
}
