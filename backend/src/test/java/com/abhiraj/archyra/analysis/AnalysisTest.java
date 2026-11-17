package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AnalysisTest {

	@Test
	void queuedAnalysisStartsAtQueuedWithZeroProgress() {
		Analysis analysis = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());

		assertThat(analysis.getRepositoryUrl()).isEqualTo("https://github.com/owner/repo");
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
		assertThat(analysis.getProgress()).isZero();
		assertThat(analysis.getFailureReason()).isNull();
	}

	@Test
	void idAndTimestampsAreAssignedByPersistenceNotTheFactory() {
		Analysis analysis = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());

		assertThat(analysis.getId()).isNull();
		assertThat(analysis.getCreatedAt()).isNull();
		assertThat(analysis.getUpdatedAt()).isNull();
	}

	@Test
	void advancingMovesStatusAndProgress() {
		Analysis analysis = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());

		analysis.advanceTo(AnalysisStatus.PARSING, 25);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.PARSING);
		assertThat(analysis.getProgress()).isEqualTo(25);
		assertThat(analysis.isFinished()).isFalse();
	}

	@Test
	void completingSetsFullProgress() {
		Analysis analysis = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());
		analysis.advanceTo(AnalysisStatus.BUILDING_GRAPH, 80);

		analysis.markCompleted();

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getProgress()).isEqualTo(100);
		assertThat(analysis.getFailureReason()).isNull();
		assertThat(analysis.isFinished()).isTrue();
	}

	@Test
	void failingKeepsProgressAndStoresTheReason() {
		Analysis analysis = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());
		analysis.advanceTo(AnalysisStatus.PARSING, 25);

		analysis.markFailed("No source files could be analysed");

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getProgress()).isEqualTo(25);
		assertThat(analysis.getFailureReason()).isEqualTo("No source files could be analysed");
		assertThat(analysis.isFinished()).isTrue();
	}

	@Test
	void aVeryLongFailureReasonIsCutToTheColumnLength() {
		Analysis analysis = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());

		analysis.markFailed("x".repeat(5000));

		assertThat(analysis.getFailureReason()).hasSize(1000);
	}

	@Test
	void aFinishedAnalysisCannotChangeAgain() {
		Analysis completed = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());
		completed.markCompleted();
		Analysis failed = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());
		failed.markFailed("boom");

		assertThatThrownBy(() -> completed.advanceTo(AnalysisStatus.PARSING, 25)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> completed.markFailed("late")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(failed::markCompleted).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> failed.advanceTo(AnalysisStatus.CLONING, 5)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void advanceToRefusesFinalStatuses() {
		Analysis analysis = Analysis.queued("https://github.com/owner/repo", java.util.UUID.randomUUID());

		assertThatThrownBy(() -> analysis.advanceTo(AnalysisStatus.COMPLETED, 100)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> analysis.advanceTo(AnalysisStatus.FAILED, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
