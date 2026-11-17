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
	 * Deletes one of the caller's analyses with its graph and metadata.
	 *
	 * @throws AnalysisNotFoundException   if it does not exist or belongs to someone else
	 * @throws AnalysisInProgressException if it is still queued or running
	 */
	public void delete(String analysisId) {
		Analysis analysis = findOwned(analysisId);
		if (!analysis.isFinished()) {
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
	 * @throws AnalysisNotCompletedException if the analysis is still running or FAILED
	 */
	public UUID requireCompleted(String analysisId) {
		Analysis analysis = findOwned(analysisId);
		return switch (analysis.getStatus()) {
			case COMPLETED -> analysis.getId();
			case FAILED -> throw new AnalysisNotCompletedException("The analysis failed, so it has no results");
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

	/** Every analysis of every user. Not ownership-scoped, so only callers that have already checked for an administrator may use it. */
	public long countAll() {
		return analysisRepository.count();
	}
}
