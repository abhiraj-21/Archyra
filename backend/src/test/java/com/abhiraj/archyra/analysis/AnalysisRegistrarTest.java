package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.common.UsageLimitExceededException;
import com.abhiraj.archyra.usage.UsageService;
import com.abhiraj.archyra.usage.UsageType;

@ExtendWith(MockitoExtension.class)
class AnalysisRegistrarTest {

	private static final String URL = "https://github.com/owner/repo";
	private static final UsageType QUOTA = UsageType.REPOSITORY_ANALYSIS;

	@Mock
	private AnalysisRepository analyses;
	@Mock
	private UsageService usage;
	private AnalysisRegistrar registrar;

	private final UUID user = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		registrar = new AnalysisRegistrar(analyses, usage);
	}

	private Analysis stored(AnalysisStatus status) {
		Analysis analysis = Analysis.queued(URL, user);
		ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(analysis, "status", status);
		return analysis;
	}

	// ---- registerOrReuse -----------------------------------------------------------------------

	@Test
	void locksThenLooksForADuplicateThenChargesThenInserts() {
		given(analyses.findFirstByOwnerIdAndRepositoryUrlAndStatusInOrderByCreatedAtDesc(user, URL, AnalysisRegistrar.IN_PROGRESS))
				.willReturn(Optional.empty());
		given(analyses.save(any(Analysis.class))).willAnswer(invocation -> invocation.getArgument(0));

		AnalysisRegistrar.Registration registration = registrar.registerOrReuse(user, URL, null);

		InOrder order = inOrder(usage, analyses);
		order.verify(usage).lock(user, QUOTA);
		order.verify(analyses).findFirstByOwnerIdAndRepositoryUrlAndStatusInOrderByCreatedAtDesc(user, URL, AnalysisRegistrar.IN_PROGRESS);
		order.verify(usage).charge(user, QUOTA);
		order.verify(analyses).save(any(Analysis.class));
		assertThat(registration.reused()).isFalse();
		assertThat(registration.analysis().isQuotaCharged()).isTrue();
		assertThat(registration.analysis().isQuotaRefunded()).isFalse();
		assertThat(registration.analysis().getStatus()).isEqualTo(AnalysisStatus.QUEUED);
		assertThat(registration.analysis().getOwnerId()).isEqualTo(user);
		assertThat(registration.analysis().getRepositoryUrl()).isEqualTo(URL);
	}

	@Test
	void aRerunRemembersWhichAnalysisItReplaces() {
		UUID replaced = UUID.randomUUID();
		given(analyses.findFirstByOwnerIdAndRepositoryUrlAndStatusInOrderByCreatedAtDesc(any(), any(), any())).willReturn(Optional.empty());
		given(analyses.save(any(Analysis.class))).willAnswer(invocation -> invocation.getArgument(0));

		Analysis created = registrar.registerOrReuse(user, URL, replaced).analysis();

		assertThat(created.getReplacesAnalysisId()).isEqualTo(replaced);
		assertThat(created.isQuotaCharged()).isTrue();
	}

	@Test
	void aReusedRunningAnalysisIsNotMadeToReplaceAnything() {
		Analysis running = stored(AnalysisStatus.PARSING);
		given(analyses.findFirstByOwnerIdAndRepositoryUrlAndStatusInOrderByCreatedAtDesc(user, URL, AnalysisRegistrar.IN_PROGRESS))
				.willReturn(Optional.of(running));

		AnalysisRegistrar.Registration registration = registrar.registerOrReuse(user, URL, UUID.randomUUID());

		assertThat(registration.reused()).isTrue();
		assertThat(registration.analysis().getReplacesAnalysisId()).isNull();
		verify(analyses, never()).save(any());
	}

	@Test
	void aRunningAnalysisOfTheSameRepositoryIsReusedWithoutAChargeOrALimitCheck() {
		Analysis running = stored(AnalysisStatus.CLONING);
		given(analyses.findFirstByOwnerIdAndRepositoryUrlAndStatusInOrderByCreatedAtDesc(user, URL, AnalysisRegistrar.IN_PROGRESS))
				.willReturn(Optional.of(running));

		AnalysisRegistrar.Registration registration = registrar.registerOrReuse(user, URL, null);

		assertThat(registration.reused()).isTrue();
		assertThat(registration.analysis()).isSameAs(running);
		verify(usage).lock(user, QUOTA);
		verify(usage, never()).charge(any(), any());
		verify(analyses, never()).save(any());
	}

	@Test
	void onlyRunningStatusesCountAsDuplicates() {
		assertThat(AnalysisRegistrar.IN_PROGRESS).containsExactlyInAnyOrder(AnalysisStatus.QUEUED, AnalysisStatus.CLONING,
				AnalysisStatus.PARSING, AnalysisStatus.ANALYZING, AnalysisStatus.BUILDING_GRAPH);
	}

	@Test
	void anExceededAllowanceInsertsNothing() {
		given(analyses.findFirstByOwnerIdAndRepositoryUrlAndStatusInOrderByCreatedAtDesc(any(), any(), any())).willReturn(Optional.empty());
		doThrow(new UsageLimitExceededException("limit")).when(usage).charge(user, QUOTA);

		assertThatThrownBy(() -> registrar.registerOrReuse(user, URL, null)).isInstanceOf(UsageLimitExceededException.class);
		verify(analyses, never()).save(any());
	}

	// ---- refundQuota ---------------------------------------------------------------------------

	@Test
	void aChargedAnalysisReturnsItsUnitToItsOwner() {
		UUID id = UUID.randomUUID();
		given(analyses.markQuotaRefunded(id)).willReturn(1);
		given(analyses.findOwnerId(id)).willReturn(Optional.of(user));

		assertThat(registrar.refundQuota(id)).isTrue();

		verify(usage).release(user, QUOTA);
	}

	@Test
	void anAnalysisThatWasNeverChargedOrIsAlreadyRefundedOrGoneChangesNothing() {
		UUID id = UUID.randomUUID();
		given(analyses.markQuotaRefunded(id)).willReturn(0); // the guarded UPDATE matched no row

		assertThat(registrar.refundQuota(id)).isFalse();

		verifyNoInteractions(usage);
		verify(analyses, never()).findOwnerId(any());
	}

	@Test
	void callingTheRefundRepeatedlyReturnsTheUnitOnce() {
		UUID id = UUID.randomUUID();
		given(analyses.markQuotaRefunded(id)).willReturn(1, 0, 0); // the database lets only the first caller through
		given(analyses.findOwnerId(id)).willReturn(Optional.of(user));

		assertThat(registrar.refundQuota(id)).isTrue();
		assertThat(registrar.refundQuota(id)).isFalse();
		assertThat(registrar.refundQuota(id)).isFalse();

		verify(usage, times(1)).release(user, QUOTA);
		verifyNoMoreInteractions(usage);
	}

	// ---- failAndRefund -------------------------------------------------------------------------

	@Test
	void failAndRefundRecordsTheReasonAndReturnsTheUnit() {
		Analysis analysis = stored(AnalysisStatus.PARSING);
		given(analyses.findById(analysis.getId())).willReturn(Optional.of(analysis));
		given(analyses.markQuotaRefunded(analysis.getId())).willReturn(1);
		given(analyses.findOwnerId(analysis.getId())).willReturn(Optional.of(user));

		registrar.failAndRefund(analysis.getId(), "boom");

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).isEqualTo("boom");
		InOrder order = inOrder(analyses, usage);
		order.verify(analyses).save(analysis);
		order.verify(analyses).markQuotaRefunded(analysis.getId());
		order.verify(usage).release(user, QUOTA);
	}

	// ---- deleteAndCredit -----------------------------------------------------------------------

	@Test
	void deletingAFreeUsersChargedAnalysisReturnsItsCreditAndDeletesTheRow() {
		Analysis analysis = stored(AnalysisStatus.COMPLETED);
		given(usage.isHeldToFreeLimit(user)).willReturn(true);
		given(analyses.markQuotaRefunded(analysis.getId())).willReturn(1);
		given(analyses.findOwnerId(analysis.getId())).willReturn(Optional.of(user));

		registrar.deleteAndCredit(analysis);

		InOrder order = inOrder(analyses, usage);
		order.verify(analyses).markQuotaRefunded(analysis.getId());
		order.verify(usage).release(user, QUOTA);
		order.verify(analyses).delete(analysis);
	}

	@Test
	void deletingAnAnalysisThatWasNeverChargedOrIsAlreadyRefundedReturnsNothing() {
		Analysis analysis = stored(AnalysisStatus.FAILED);
		given(usage.isHeldToFreeLimit(user)).willReturn(true);
		given(analyses.markQuotaRefunded(analysis.getId())).willReturn(0); // the guarded UPDATE matched no row

		registrar.deleteAndCredit(analysis);

		verify(usage, never()).release(any(), any());
		verify(analyses).delete(analysis);
	}

	@Test
	void deletingAPremiumUsersAnalysisReturnsNothing() {
		Analysis analysis = stored(AnalysisStatus.COMPLETED);
		given(usage.isHeldToFreeLimit(user)).willReturn(false);

		registrar.deleteAndCredit(analysis);

		verify(analyses, never()).markQuotaRefunded(any());
		verify(usage, never()).release(any(), any());
		verify(analyses).delete(analysis);
	}

	@Test
	void deletingAnAnalysisWithoutAnOwnerReturnsNothing() {
		Analysis analysis = stored(AnalysisStatus.COMPLETED);
		ReflectionTestUtils.setField(analysis, "ownerId", null);

		registrar.deleteAndCredit(analysis);

		verifyNoInteractions(usage);
		verify(analyses).delete(analysis);
	}
}
