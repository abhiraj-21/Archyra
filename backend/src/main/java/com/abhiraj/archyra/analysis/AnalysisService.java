package com.abhiraj.archyra.analysis;

import java.util.concurrent.RejectedExecutionException;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.UUID;

import com.abhiraj.archyra.common.AnalysisInProgressException;
import com.abhiraj.archyra.common.AnalysisNotCompletedException;
import com.abhiraj.archyra.common.AnalysisNotFoundException;
import com.abhiraj.archyra.common.AnalysisNotRestorableException;
import com.abhiraj.archyra.common.InvalidRequestException;
import com.abhiraj.archyra.common.RateLimitedException;
import com.abhiraj.archyra.common.ServiceBusyException;
import com.abhiraj.archyra.security.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

	static final int DEFAULT_PAGE_SIZE = 50;
	static final int MAX_PAGE_SIZE = 200;

	private final AnalysisRepository analysisRepository;
	private final AnalysisPipeline analysisPipeline;
	private final AnalysisAccess access;
	private final AnalysisCleanup cleanup;
	private final AnalysisRegistrar registrar;
	private final RateLimiter rateLimiter;

	/**
	 * Registers a new {@link AnalysisStatus#QUEUED} analysis owned by the signed-in caller, hands it to the
	 * background pipeline and returns its identifier and status without waiting for the analysis. A free user pays
	 * one unit of the lifetime allowance; asking again for a repository that is still being analysed returns that
	 * analysis instead, free of charge.
	 *
	 * <p>Not transactional on purpose: the registrar commits the row before the pipeline thread looks it up.
	 *
	 * @throws com.abhiraj.archyra.common.UsageLimitExceededException if a free user has used all of the allowance
	 * @throws ServiceBusyException if the analysis queue is full, or other requests of the same user hold the quota lock
	 */
	public CreateAnalysisResponse createAnalysis(CreateAnalysisRequest request) {
		return start(request.repositoryUrl(), null);
	}

	/**
	 * Runs one of the caller's analyses again. It goes through the same path as a new analysis, so it is a charge
	 * against the allowance for a free user, and a re-run of a repository that is still being analysed (this analysis
	 * or a newer one) returns that running analysis instead. A new analysis with a new id is created; once it
	 * COMPLETES it replaces this one, which is deleted (graph, metadata and row) for every plan. If the re-run fails
	 * the old results are untouched.
	 *
	 * @throws AnalysisNotFoundException if the id is malformed, no such analysis exists, or it is someone else's
	 * @throws com.abhiraj.archyra.common.UsageLimitExceededException if a free user has used all of the allowance
	 * @throws ServiceBusyException if the analysis queue is full
	 */
	public CreateAnalysisResponse reanalyze(String analysisId) {
		Analysis source = findOwned(analysisId);
		if (source.getStatus() == AnalysisStatus.NOT_ANALYZED) {
			// would charge a second credit for a repository whose credit is already attached to this entry
			throw new AnalysisNotCompletedException("This repository has not been analysed yet. Analyse it to use the credit you already spent.");
		}
		return start(source.getRepositoryUrl(), source.isFinished() ? source.getId() : null);
	}

	private CreateAnalysisResponse start(String repositoryUrl, UUID replacesAnalysisId) {
		UUID owner = access.callerId();
		RateLimiter.Decision decision = rateLimiter.tryConsume(RateLimiter.Rule.CREATE_ANALYSIS, owner.toString());
		if (!decision.allowed()) {
			throw new RateLimitedException(decision.retryAfterSeconds()); // every analysis is a git clone
		}

		AnalysisRegistrar.Registration registration;
		try {
			registration = registrar.registerOrReuse(owner, repositoryUrl, replacesAnalysisId);
		}
		catch (PessimisticLockingFailureException ex) {
			log.warn("Gave up waiting for the usage lock of a user: {}", ex.getClass().getSimpleName());
			throw new ServiceBusyException("Too many requests are being processed for your account. Please try again.");
		}
		Analysis analysis = registration.analysis();
		if (registration.reused()) {
			log.info("Analysis {} is already running for {}; not starting another", analysis.getId(), analysis.getRepositoryUrl());
			return new CreateAnalysisResponse(analysis.getId().toString(), analysis.getStatus());
		}
		log.info("Analysis {} created with status {} for {}",
				analysis.getId(), analysis.getStatus(), analysis.getRepositoryUrl());

		try {
			analysisPipeline.run(analysis.getId());
		}
		catch (RejectedExecutionException ex) { // includes Spring's TaskRejectedException
			refundAndFail(analysis.getId(), "The server is busy; the analysis queue is full");
			throw new ServiceBusyException("Too many analyses are running. Please try again later.");
		}
		return new CreateAnalysisResponse(analysis.getId().toString(), analysis.getStatus());
	}

	/**
	 * Analyses a restored {@link AnalysisStatus#NOT_ANALYZED} entry of the caller. It moves the <b>same row</b> to
	 * {@code QUEUED} and starts the pipeline <b>without charging</b>: the credit was spent by the deleted account and came
	 * back with the entry. One guarded UPDATE decides who starts, so two simultaneous calls run one pipeline and the
	 * other gets the conflict. It is a repository clone like any analysis, so it uses the same per-user rate limit. If the
	 * queue is full the entry returns to {@code NOT_ANALYZED} (nothing lost, nothing refunded); if the run later fails the
	 * ordinary rule applies (the credit goes back once and the row ends {@code FAILED}).
	 *
	 * @throws AnalysisNotFoundException if the id is malformed, unknown or someone else's
	 * @throws AnalysisNotRestorableException if the analysis is not a NOT_ANALYZED entry (running, completed or failed)
	 * @throws ServiceBusyException if the analysis queue is full, or other requests of the same user hold the quota lock
	 */
	public CreateAnalysisResponse analyze(String analysisId) {
		Analysis entry = findOwned(analysisId);
		UUID owner = access.callerId();
		if (entry.getStatus() != AnalysisStatus.NOT_ANALYZED) {
			throw new AnalysisNotRestorableException("Only a repository that has not been analysed yet can be analysed this way");
		}
		RateLimiter.Decision decision = rateLimiter.tryConsume(RateLimiter.Rule.CREATE_ANALYSIS, owner.toString());
		if (!decision.allowed()) {
			throw new RateLimitedException(decision.retryAfterSeconds());
		}
		boolean started;
		try {
			started = registrar.startRestored(entry.getId(), owner);
		}
		catch (PessimisticLockingFailureException ex) {
			log.warn("Gave up waiting for the usage lock of a user: {}", ex.getClass().getSimpleName());
			throw new ServiceBusyException("Too many requests are being processed for your account. Please try again.");
		}
		if (!started) {
			throw new AnalysisNotRestorableException("Only a repository that has not been analysed yet can be analysed this way");
		}
		log.info("Restored analysis {} is being analysed; its credit was already spent", entry.getId());
		try {
			analysisPipeline.run(entry.getId());
		}
		catch (RejectedExecutionException ex) {
			registrar.returnRestored(entry.getId());
			throw new ServiceBusyException("Too many analyses are running. Please try again later.");
		}
		return new CreateAnalysisResponse(entry.getId().toString(), AnalysisStatus.QUEUED);
	}

	/** The row stays as FAILED, as before, and the unit goes back. A failure here must not hide the 503. */
	private void refundAndFail(UUID analysisId, String reason) {
		try {
			registrar.failAndRefund(analysisId, reason);
		}
		catch (RuntimeException ex) {
			log.error("Could not mark analysis {} as FAILED and return its quota unit; startup repair will retry", analysisId, ex);
		}
	}

	/**
	 * Reads the current status and progress of one of the caller's analyses from PostgreSQL, plus the failure
	 * reason when it FAILED.
	 *
	 * @param analysisId the id as it appears in the URL; anything that is not a UUID cannot exist
	 * @throws AnalysisNotFoundException if the id is malformed, no such analysis exists, or it belongs to someone else
	 */
	public AnalysisStatusResponse getStatus(String analysisId) {
		return AnalysisStatusResponse.from(findOwned(analysisId));
	}

	/**
	 * The caller's own analyses, newest first. Other users' analyses never appear, whatever their role.
	 *
	 * @param page zero-based page number
	 * @param size page size, 1 to {@value #MAX_PAGE_SIZE}
	 * @throws InvalidRequestException if page or size is out of range
	 */
	public AnalysisPageResponse list(int page, int size) {
		if (page < 0) {
			throw new InvalidRequestException("page must not be negative");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		Page<Analysis> result = analysisRepository.findByOwnerId(access.callerId(),
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
		return new AnalysisPageResponse(result.getContent().stream().map(AnalysisSummary::from).toList(),
				page, size, result.getTotalElements(), result.getTotalPages());
	}

	/**
	 * Deletes one of the caller's analyses with its graph and metadata. A restored {@code NOT_ANALYZED} entry goes through
	 * the same path (there is nothing to remove but the row) and, like any analysis, returns exactly one credit to a free user.
	 *
	 * @throws AnalysisNotFoundException   if it does not exist or belongs to someone else
	 * @throws AnalysisInProgressException if it is still queued or running
	 */
	public void delete(String analysisId) {
		Analysis analysis = findOwned(analysisId);
		if (analysis.isInProgress()) { // a NOT_ANALYZED entry can be deleted: it holds a credit and nothing else
			throw new AnalysisInProgressException("The analysis is still running; try again when it has finished");
		}
		cleanup.delete(analysis);
		log.info("Analysis {} deleted by its owner", analysis.getId());
	}

	/**
	 * Checks that the caller owns an analysis and that it has finished successfully, for endpoints that serve its
	 * results. Every such endpoint goes through here, so this is where ownership is enforced.
	 *
	 * @return the analysis id, which is also the {@code analysisId} of its graph
	 * @throws AnalysisNotFoundException     if the id is malformed, no such analysis exists, or it is someone else's
	 * @throws AnalysisNotCompletedException if the analysis is still running, FAILED, or a restored NOT_ANALYZED entry
	 */
	public UUID requireCompleted(String analysisId) {
		Analysis analysis = findOwned(analysisId);
		return switch (analysis.getStatus()) {
			case COMPLETED -> analysis.getId();
			case FAILED -> throw new AnalysisNotCompletedException("The analysis failed, so it has no results");
			case NOT_ANALYZED -> throw new AnalysisNotCompletedException(
					"This repository has not been analysed yet. Analyse it to see its results");
			default -> throw new AnalysisNotCompletedException(
					"The analysis has not completed yet (status: " + analysis.getStatus() + ")");
		};
	}

	/**
	 * The analysis if the caller owns it. Unknown, malformed, foreign and ownerless (pre-accounts) ids all give the
	 * same 404, so ids cannot be probed.
	 */
	private Analysis findOwned(String analysisId) {
		UUID caller = access.callerId();
		return analysisRepository.findById(parseId(analysisId))
				.filter(analysis -> caller.equals(analysis.getOwnerId()))
				.orElseThrow(AnalysisService::notFound);
	}

	/** Accepts only the canonical UUID form, so loose inputs such as {@code 1-1-1-1-1} are rejected. */
	private static UUID parseId(String analysisId) {
		try {
			UUID id = UUID.fromString(analysisId);
			if (id.toString().equalsIgnoreCase(analysisId)) {
				return id;
			}
		}
		catch (IllegalArgumentException ex) {
			// falls through to not found
		}
		throw notFound();
	}

	/** The message does not echo the id: it is user input. */
	private static AnalysisNotFoundException notFound() {
		return new AnalysisNotFoundException("Analysis not found");
	}

	/**
	 * Whether the analysis exists and has COMPLETED. Not ownership-scoped: for background jobs of other features that
	 * hold an id they got from an event, never for request handling (that goes through {@link #requireCompleted}).
	 */
	public boolean isCompleted(UUID analysisId) {
		return analysisRepository.existsByIdAndStatus(analysisId, AnalysisStatus.COMPLETED);
	}

	/** Of the given ids, those of analyses that exist and have COMPLETED. Not ownership-scoped (see {@link #isCompleted}). */
	public java.util.Set<UUID> completedAmong(java.util.Collection<UUID> analysisIds) {
		if (analysisIds.isEmpty()) {
			return java.util.Set.of();
		}
		return new java.util.HashSet<>(analysisRepository.findIdsByStatusAndIdIn(AnalysisStatus.COMPLETED, analysisIds));
	}

	/** Every analysis of every user. Not ownership-scoped, so only callers that have already checked for an administrator may use it. */
	public long countAll() {
		return analysisRepository.count();
	}

	/**
	 * The repository URL of an analysis. Not ownership-scoped, so only for other features' code that already passed
	 * {@link #requireCompleted} for the id (Ask AI's project context), never for request handling on its own.
	 */
	public java.util.Optional<String> repositoryUrlOf(UUID analysisId) {
		return analysisRepository.findById(analysisId).map(Analysis::getRepositoryUrl);
	}
}
