package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.common.AnalysisNotCompletedException;
import com.abhiraj.archyra.common.AnalysisNotFoundException;
import com.abhiraj.archyra.common.ServiceBusyException;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

	private static final String URL = "https://github.com/owner/repo";
	private static final UUID CALLER = UUID.randomUUID();

	@Mock
	private AnalysisRepository analysisRepository;

	@Mock
	private AnalysisPipeline analysisPipeline;

	@Mock
	private AnalysisAccess access;

	@Mock
	private AnalysisCleanup cleanup;

	@Mock
	private com.abhiraj.archyra.security.RateLimiter rateLimiter;

	private AnalysisService service;

	@BeforeEach
	void setUp() {
		service = new AnalysisService(analysisRepository, analysisPipeline, access, cleanup, rateLimiter);
		lenient().when(access.callerId()).thenReturn(CALLER);
		lenient().when(rateLimiter.tryConsume(any(), any()))
				.thenReturn(new com.abhiraj.archyra.security.RateLimiter.Decision(true, 0));
		// Simulate the persistence layer assigning a generated id on save.
		lenient().when(analysisRepository.save(any(Analysis.class))).thenAnswer(invocation -> {
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
		assertThat(saved.getValue().getOwnerId()).isEqualTo(CALLER);
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

	@Test
	void statusReturnsIdStatusAndProgressOfTheStoredAnalysis() {
		UUID id = UUID.randomUUID();
		Analysis analysis = Analysis.queued(URL, CALLER);
		ReflectionTestUtils.setField(analysis, "id", id);
		analysis.advanceTo(AnalysisStatus.PARSING, 25);
		when(analysisRepository.findById(id)).thenReturn(Optional.of(analysis));

		AnalysisStatusResponse response = service.getStatus(id.toString());

		assertThat(response).isEqualTo(new AnalysisStatusResponse(id.toString(), AnalysisStatus.PARSING, 25, null));
	}

	@Test
	void statusOfFailedAnalysisIncludesTheFailureReason() {
		UUID id = UUID.randomUUID();
		Analysis analysis = Analysis.queued(URL, CALLER);
		ReflectionTestUtils.setField(analysis, "id", id);
		analysis.advanceTo(AnalysisStatus.PARSING, 25);
		analysis.markFailed("Repository is too large");
		when(analysisRepository.findById(id)).thenReturn(Optional.of(analysis));

		assertThat(service.getStatus(id.toString())).isEqualTo(
				new AnalysisStatusResponse(id.toString(), AnalysisStatus.FAILED, 25, "Repository is too large"));
	}

	@Test
	void statusOfCompletedAnalysisHasNoFailureReason() {
		UUID id = UUID.randomUUID();
		Analysis analysis = Analysis.queued(URL, CALLER);
		ReflectionTestUtils.setField(analysis, "id", id);
		analysis.markCompleted();
		when(analysisRepository.findById(id)).thenReturn(Optional.of(analysis));

		assertThat(service.getStatus(id.toString()).failureReason()).isNull();
	}

	@Test
	void statusAcceptsUpperCaseIds() {
		UUID id = UUID.randomUUID();
		Analysis analysis = Analysis.queued(URL, CALLER);
		ReflectionTestUtils.setField(analysis, "id", id);
		when(analysisRepository.findById(id)).thenReturn(Optional.of(analysis));

		assertThat(service.getStatus(id.toString().toUpperCase()).analysisId()).isEqualTo(id.toString());
	}

	@Test
	void statusOfUnknownAnalysisIsNotFound() {
		UUID id = UUID.randomUUID();
		when(analysisRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getStatus(id.toString()))
				.isInstanceOf(AnalysisNotFoundException.class)
				.hasMessage("Analysis not found");
	}

	@ParameterizedTest
	@ValueSource(strings = {"a8f31c", "not-a-uuid", "1-1-1-1-1", " ", "../etc/passwd",
			"123e4567-e89b-12d3-a456-4266141740000"})
	void statusOfMalformedIdIsNotFoundWithoutQueryingTheDatabase(String analysisId) {
		assertThatThrownBy(() -> service.getStatus(analysisId)).isInstanceOf(AnalysisNotFoundException.class);
		verifyNoInteractions(analysisRepository);
	}

	// ---- requireCompleted ----------------------------------------------------------------------

	private Analysis stored(UUID id, AnalysisStatus status) {
		Analysis analysis = Analysis.queued(URL, CALLER);
		ReflectionTestUtils.setField(analysis, "id", id);
		ReflectionTestUtils.setField(analysis, "status", status);
		when(analysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		return analysis;
	}

	@Test
	void requireCompletedReturnsTheIdOfACompletedAnalysis() {
		UUID id = UUID.randomUUID();
		stored(id, AnalysisStatus.COMPLETED);

		assertThat(service.requireCompleted(id.toString())).isEqualTo(id);
	}

	@ParameterizedTest
	@ValueSource(strings = {"QUEUED", "CLONING", "PARSING", "ANALYZING", "BUILDING_GRAPH"})
	void requireCompletedRejectsAnAnalysisThatIsStillRunning(String status) {
		UUID id = UUID.randomUUID();
		stored(id, AnalysisStatus.valueOf(status));

		assertThatThrownBy(() -> service.requireCompleted(id.toString()))
				.isInstanceOf(AnalysisNotCompletedException.class)
				.hasMessageContaining("not completed yet").hasMessageContaining(status);
	}

	@Test
	void requireCompletedRejectsAFailedAnalysis() {
		UUID id = UUID.randomUUID();
		stored(id, AnalysisStatus.FAILED);

		assertThatThrownBy(() -> service.requireCompleted(id.toString()))
				.isInstanceOf(AnalysisNotCompletedException.class).hasMessageContaining("failed");
	}

	@Test
	void requireCompletedOfUnknownOrMalformedIdIsNotFound() {
		UUID id = UUID.randomUUID();
		when(analysisRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.requireCompleted(id.toString())).isInstanceOf(AnalysisNotFoundException.class);
		assertThatThrownBy(() -> service.requireCompleted("a8f31c")).isInstanceOf(AnalysisNotFoundException.class);
	}

	// ---- ownership -----------------------------------------------------------------------------

	private Analysis ownedBy(UUID owner, UUID id, AnalysisStatus status) {
		Analysis analysis = owner == null ? ownerless() : Analysis.queued(URL, owner);
		ReflectionTestUtils.setField(analysis, "id", id);
		ReflectionTestUtils.setField(analysis, "status", status);
		when(analysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		return analysis;
	}

	private static Analysis ownerless() {
		Analysis analysis = Analysis.queued(URL, UUID.randomUUID());
		ReflectionTestUtils.setField(analysis, "ownerId", null); // a row from before accounts existed
		return analysis;
	}

	@Test
	void anotherUsersAnalysisIsNotFoundForStatusAndResultsAlike() {
		UUID id = UUID.randomUUID();
		ownedBy(UUID.randomUUID(), id, AnalysisStatus.COMPLETED);

		assertThatThrownBy(() -> service.getStatus(id.toString())).isInstanceOf(AnalysisNotFoundException.class)
				.hasMessage("Analysis not found");
		assertThatThrownBy(() -> service.requireCompleted(id.toString())).isInstanceOf(AnalysisNotFoundException.class);
		assertThatThrownBy(() -> service.delete(id.toString())).isInstanceOf(AnalysisNotFoundException.class);
		verifyNoInteractions(cleanup);
	}

	@Test
	void anAnalysisWithoutAnOwnerIsVisibleToNobody() {
		UUID id = UUID.randomUUID();
		ownedBy(null, id, AnalysisStatus.COMPLETED);

		assertThatThrownBy(() -> service.getStatus(id.toString())).isInstanceOf(AnalysisNotFoundException.class);
		assertThatThrownBy(() -> service.requireCompleted(id.toString())).isInstanceOf(AnalysisNotFoundException.class);
	}

	@Test
	void theOwnerSeesTheirOwnAnalysis() {
		UUID id = UUID.randomUUID();
		ownedBy(CALLER, id, AnalysisStatus.COMPLETED);

		assertThat(service.getStatus(id.toString()).analysisId()).isEqualTo(id.toString());
		assertThat(service.requireCompleted(id.toString())).isEqualTo(id);
	}

	// ---- list ----------------------------------------------------------------------------------

	@Test
	void listReturnsOnlyTheCallersAnalysesNewestFirst() {
		UUID id = UUID.randomUUID();
		Analysis analysis = Analysis.queued(URL, CALLER);
		ReflectionTestUtils.setField(analysis, "id", id);
		analysis.markCompleted();
		when(analysisRepository.findByOwnerId(any(UUID.class), any(org.springframework.data.domain.Pageable.class)))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(analysis),
						org.springframework.data.domain.PageRequest.of(1, 10), 11));

		AnalysisPageResponse response = service.list(1, 10);

		ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
				ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
		verify(analysisRepository).findByOwnerId(org.mockito.ArgumentMatchers.eq(CALLER), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
		assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
		assertThat(response.items()).extracting(AnalysisSummary::analysisId).containsExactly(id.toString());
		assertThat(response.totalElements()).isEqualTo(11);
		assertThat(response.totalPages()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, 201})
	void listRejectsASizeOutOfRange(int size) {
		assertThatThrownBy(() -> service.list(0, size)).isInstanceOf(com.abhiraj.archyra.common.InvalidRequestException.class);
		verifyNoInteractions(analysisRepository);
	}

	@Test
	void listRejectsANegativePage() {
		assertThatThrownBy(() -> service.list(-1, 10)).isInstanceOf(com.abhiraj.archyra.common.InvalidRequestException.class);
	}

	// ---- delete --------------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = {"COMPLETED", "FAILED"})
	void deleteRemovesAFinishedAnalysis(String status) {
		UUID id = UUID.randomUUID();
		Analysis analysis = ownedBy(CALLER, id, AnalysisStatus.valueOf(status));

		service.delete(id.toString());

		verify(cleanup).delete(analysis);
	}

	@ParameterizedTest
	@ValueSource(strings = {"QUEUED", "CLONING", "PARSING", "ANALYZING", "BUILDING_GRAPH"})
	void deleteRefusesAnAnalysisThatIsStillRunning(String status) {
		UUID id = UUID.randomUUID();
		ownedBy(CALLER, id, AnalysisStatus.valueOf(status));

		assertThatThrownBy(() -> service.delete(id.toString()))
				.isInstanceOf(com.abhiraj.archyra.common.AnalysisInProgressException.class);
		verifyNoInteractions(cleanup);
	}

	// ---- rate limit ----------------------------------------------------------------------------

	@Test
	void startingAnAnalysisIsLimitedPerUserBeforeAnythingIsSaved() {
		when(rateLimiter.tryConsume(com.abhiraj.archyra.security.RateLimiter.Rule.CREATE_ANALYSIS, CALLER.toString()))
				.thenReturn(new com.abhiraj.archyra.security.RateLimiter.Decision(false, 1800));

		assertThatThrownBy(() -> service.createAnalysis(new CreateAnalysisRequest(URL)))
				.isInstanceOfSatisfying(com.abhiraj.archyra.common.RateLimitedException.class,
						ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(1800));
		verifyNoInteractions(analysisRepository, analysisPipeline);
	}
}
