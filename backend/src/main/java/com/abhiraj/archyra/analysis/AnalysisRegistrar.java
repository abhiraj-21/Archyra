package com.abhiraj.archyra.analysis;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.usage.UsageService;
import com.abhiraj.archyra.usage.UsageType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The only way an analysis is created, and the only way its quota unit is returned. It is a separate bean from
 * {@link AnalysisService} so that {@code @Transactional} goes through the proxy: {@code createAnalysis} itself must
 * stay non-transactional, because the row has to be committed before the background pipeline looks it up.
 *
 * <p><b>Never call {@link AnalysisPipeline#run} for a new analysis without going through here.</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AnalysisRegistrar {

	/** An analysis in one of these statuses is still running, so an identical request joins it. */
	static final List<AnalysisStatus> IN_PROGRESS = List.of(AnalysisStatus.QUEUED, AnalysisStatus.CLONING,
			AnalysisStatus.PARSING, AnalysisStatus.ANALYZING, AnalysisStatus.BUILDING_GRAPH);

	static final UsageType QUOTA = UsageType.REPOSITORY_ANALYSIS;

	/** @param reused true when an identical analysis was already running: nothing was charged and none must be started */
	record Registration(Analysis analysis, boolean reused) {
	}

	private final AnalysisRepository analysisRepository;
	private final UsageService usageService;

	/**
	 * One short transaction, in this order, all under the row lock of this user's counter (so it serializes this
	 * user's requests only):
	 * <ol>
	 * <li>duplicate check: a running analysis of the same repository is returned, free of charge, even at the limit;</li>
	 * <li>limit check and increment;</li>
	 * <li>insert the analysis as {@code QUEUED}, charged.</li>
	 * </ol>
	 * The unit and the row commit together or not at all.
	 *
	 * @throws com.abhiraj.archyra.common.UsageLimitExceededException if a free user has used up the allowance
	 */
	@Transactional
	Registration registerOrReuse(UUID userId, String repositoryUrl) {
		usageService.lock(userId, QUOTA);

		Optional<Analysis> running = analysisRepository
				.findFirstByOwnerIdAndRepositoryUrlAndStatusInOrderByCreatedAtDesc(userId, repositoryUrl, IN_PROGRESS);
		if (running.isPresent()) {
			return new Registration(running.get(), true);
		}

		usageService.charge(userId, QUOTA);
		Analysis analysis = Analysis.queued(repositoryUrl, userId);
		analysis.markQuotaCharged();
		return new Registration(analysisRepository.save(analysis), false);
	}

	/**
	 * Ends an analysis as FAILED and returns its unit, in one transaction: nobody can see FAILED and still be
	 * charged, and if the refund fails the status is not written either (the caller then falls back, see
	 * {@link AnalysisPipeline}).
	 */
	@Transactional
	void failAndRefund(UUID analysisId, String reason) {
		Analysis analysis = analysisRepository.findById(analysisId)
				.orElseThrow(() -> new IllegalStateException("Analysis " + analysisId + " disappeared"));
		analysis.markFailed(reason);
		analysisRepository.save(analysis);
		refundQuota(analysisId);
	}

	/**
	 * Returns the unit this analysis consumed, at most once however often and from however many threads this is
	 * called: the guarded UPDATE in step 1 only succeeds for the first caller. Nothing happens for an analysis that
	 * was never charged, is already refunded, or no longer exists (deleting an analysis never gives a unit back).
	 *
	 * @return true if a unit was returned by this call
	 */
	@Transactional
	boolean refundQuota(UUID analysisId) {
		if (analysisRepository.markQuotaRefunded(analysisId) == 0) {
			return false;
		}
		UUID owner = analysisRepository.findOwnerId(analysisId)
				.orElseThrow(() -> new IllegalStateException("Analysis " + analysisId + " disappeared during a refund"));
		usageService.release(owner, QUOTA);
		log.info("Returned one {} unit for analysis {}", QUOTA, analysisId);
		return true;
	}
}
