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
	private AnalysisRegistrar registrar;

	@Mock
	private com.abhiraj.archyra.security.RateLimiter rateLimiter;

	private AnalysisService service;

	@BeforeEach
	void setUp() {
		service = new AnalysisService(analysisRepository, analysisPipeline, access, cleanup, registrar, rateLimiter);
		lenient().when(access.callerId()).thenReturn(CALLER);
		lenient().when(rateLimiter.tryConsume(any(), any()))
				.thenReturn(new com.abhiraj.archyra.security.RateLimiter.Decision(true, 0));
		// Simulate the registrar: a new charged analysis with a generated id.
		lenient().when(registrar.registerOrReuse(any(), any(), any())).thenAnswer(invocation -> {
			Analysis analysis = Analysis.queued(invocation.getArgument(1), invocation.getArgument(0));
			ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
			return new AnalysisRegistrar.Registration(analysis, false);
		});
	}

	private static Analysis running(AnalysisStatus status) {
		Analysis analysis = Analysis.queued(URL, CALLER);
		ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(analysis, "status", status);
		return analysis;
	}

	@Test
	void registersTheAnalysisForTheCallerAndTheRequestedRepository() {
		service.createAnalysis(new CreateAnalysisRequest(URL));

		verify(registrar).registerOrReuse(CALLER, URL, null);
	}

	@Test
	void responseCarriesThePersistedIdAndQueuedStatus() {
		CreateAnalysisResponse response = service.createAnalysis(new CreateAnalysisRequest(URL));

		ArgumentCaptor<UUID> started = ArgumentCaptor.forClass(UUID.class);
		verify(analysisPipeline).run(started.capture());
		assertThat(response.analysisId()).isEqualTo(started.getValue().toString());
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
	void startsThePipelineOnlyAfterTheAnalysisIsRegistered() {
		CreateAnalysisResponse response = service.createAnalysis(new CreateAnalysisRequest(URL));

		InOrder order = inOrder(registrar, analysisPipeline);
		order.verify(registrar).registerOrReuse(CALLER, URL, null);
		order.verify(analysisPipeline).run(UUID.fromString(response.analysisId()));
	}

	@Test
	void aRepositoryThatIsAlreadyRunningReturnsTheExistingAnalysisWithoutStartingAnother() {
		Analysis existing = running(AnalysisStatus.PARSING);
		when(registrar.registerOrReuse(CALLER, URL, null)).thenReturn(new AnalysisRegistrar.Registration(existing, true));

		CreateAnalysisResponse response = service.createAnalysis(new CreateAnalysisRequest(URL));

		assertThat(response).isEqualTo(new CreateAnalysisResponse(existing.getId().toString(), AnalysisStatus.PARSING));
		verifyNoInteractions(analysisPipeline);
	}

	@Test
	void anExceededAllowanceStartsNothing() {
		doThrow(new com.abhiraj.archyra.common.UsageLimitExceededException("limit")).when(registrar).registerOrReuse(any(), any(), any());

		assertThatThrownBy(() -> service.createAnalysis(new CreateAnalysisRequest(URL)))
				.isInstanceOf(com.abhiraj.archyra.common.UsageLimitExceededException.class);
		verifyNoInteractions(analysisPipeline);
	}

	@Test
	void waitingTooLongForTheUsageLockIsAnsweredAsBusy() {
		doThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout")).when(registrar).registerOrReuse(any(), any(), any());

		assertThatThrownBy(() -> service.createAnalysis(new CreateAnalysisRequest(URL))).isInstanceOf(ServiceBusyException.class);
		verifyNoInteractions(analysisPipeline);
	}

	@Test
	void failsTheAnalysisReturnsTheUnitAndSignalsBusyWhenTheQueueIsFull() {
		doThrow(new TaskRejectedException("queue full")).when(analysisPipeline).run(any());

		assertThatThrownBy(() -> service.createAnalysis(new CreateAnalysisRequest(URL)))
				.isInstanceOf(ServiceBusyException.class);

		ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
		verify(registrar).failAndRefund(id.capture(), org.mockito.ArgumentMatchers.contains("queue is full"));
		verify(analysisPipeline).run(id.getValue());
	}

	@Test
	void aFailingRefundDoesNotHideTheBusyAnswer() {
		doThrow(new TaskRejectedException("queue full")).when(analysisPipeline).run(any());
		doThrow(new IllegalStateException("db down")).when(registrar).failAndRefund(any(), any());

		assertThatThrownBy(() -> service.createAnalysis(new CreateAnalysisRequest(URL)))
				.isInstanceOf(ServiceBusyException.class);
	}

	// ---- reanalyze -----------------------------------------------------------------------------

	private Analysis owned(UUID id, AnalysisStatus status) {
		Analysis analysis = Analysis.queued(URL, CALLER);
		ReflectionTestUtils.setField(analysis, "id", id);
		ReflectionTestUtils.setField(analysis, "status", status);
		when(analysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		return analysis;
	}

	@ParameterizedTest
	@ValueSource(strings = {"COMPLETED", "FAILED"})
	void reanalyzingAFinishedAnalysisRegistersANewOneThatReplacesIt(String status) {
		UUID source = UUID.randomUUID();
		owned(source, AnalysisStatus.valueOf(status));

		CreateAnalysisResponse response = service.reanalyze(source.toString());

		verify(registrar).registerOrReuse(CALLER, URL, source);
		ArgumentCaptor<UUID> started = ArgumentCaptor.forClass(UUID.class);
		verify(analysisPipeline).run(started.capture());
		assertThat(response.analysisId()).isEqualTo(started.getValue().toString()).isNotEqualTo(source.toString());
		assertThat(response.status()).isEqualTo(AnalysisStatus.QUEUED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"QUEUED", "CLONING", "PARSING", "ANALYZING", "BUILDING_GRAPH"})
	void reanalyzingARunningAnalysisReplacesNothingAndReturnsTheRunningOne(String status) {
		UUID source = UUID.randomUUID();
		Analysis running = owned(source, AnalysisStatus.valueOf(status));
		when(registrar.registerOrReuse(CALLER, URL, null)).thenReturn(new AnalysisRegistrar.Registration(running, true));

		CreateAnalysisResponse response = service.reanalyze(source.toString());

		assertThat(response).isEqualTo(new CreateAnalysisResponse(source.toString(), AnalysisStatus.valueOf(status)));
		verifyNoInteractions(analysisPipeline);
	}

	@Test
	void reanalyzingAnotherUsersAnalysisIsNotFoundAndStartsNothing() {
		UUID source = UUID.randomUUID();
		Analysis foreign = Analysis.queued(URL, UUID.randomUUID());
		ReflectionTestUtils.setField(foreign, "id", source);
		when(analysisRepository.findById(source)).thenReturn(Optional.of(foreign));

		assertThatThrownBy(() -> service.reanalyze(source.toString())).isInstanceOf(AnalysisNotFoundException.class);
		verifyNoInteractions(registrar, analysisPipeline);
	}

	@Test
	void reanalyzingAnUnknownOrMalformedIdIsNotFound() {
		UUID unknown = UUID.randomUUID();
		when(analysisRepository.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.reanalyze(unknown.toString())).isInstanceOf(AnalysisNotFoundException.class);
		assertThatThrownBy(() -> service.reanalyze("not-a-uuid")).isInstanceOf(AnalysisNotFoundException.class);
		verifyNoInteractions(registrar, analysisPipeline);
	}

	@Test
	void reanalyzingAtTheLimitIsRefusedLikeAnyNewAnalysis() {
		UUID source = UUID.randomUUID();
		owned(source, AnalysisStatus.COMPLETED);
		doThrow(new com.abhiraj.archyra.common.UsageLimitExceededException("limit")).when(registrar).registerOrReuse(any(), any(), any());

		assertThatThrownBy(() -> service.reanalyze(source.toString()))
				.isInstanceOf(com.abhiraj.archyra.common.UsageLimitExceededException.class);
		verifyNoInteractions(analysisPipeline);
	}

	@Test
	void aRejectedReanalysisFailsItsOwnNewAnalysisReturnsTheUnitAndSignalsBusy() {
		UUID source = UUID.randomUUID();
		owned(source, AnalysisStatus.COMPLETED);
		doThrow(new TaskRejectedException("queue full")).when(analysisPipeline).run(any());

		assertThatThrownBy(() -> service.reanalyze(source.toString())).isInstanceOf(ServiceBusyException.class);

		ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
		verify(registrar).failAndRefund(id.capture(), org.mockito.ArgumentMatchers.contains("queue is full"));
		assertThat(id.getValue()).isNotEqualTo(source); // the old analysis is not touched
	}

	@Test
	void reanalyzingIsRateLimitedLikeCreating() {
		UUID source = UUID.randomUUID();
		owned(source, AnalysisStatus.COMPLETED);
		when(rateLimiter.tryConsume(any(), any())).thenReturn(new com.abhiraj.archyra.security.RateLimiter.Decision(false, 30));

		assertThatThrownBy(() -> service.reanalyze(source.toString())).isInstanceOf(com.abhiraj.archyra.common.RateLimitedException.class);
		verifyNoInteractions(registrar, analysisPipeline);
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
