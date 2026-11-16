package com.abhiraj.archyra.analysis;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

import com.abhiraj.archyra.common.AnalysisNotCompletedException;
import com.abhiraj.archyra.common.AnalysisNotFoundException;
import com.abhiraj.archyra.common.ServiceBusyException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

	private final AnalysisRepository analysisRepository;
	private final AnalysisPipeline analysisPipeline;

	/**
	 * Persists a new {@link AnalysisStatus#QUEUED} analysis, hands it to the background pipeline and
	 * returns its identifier and status without waiting for the analysis.
	 *
	 * <p>Not transactional on purpose: the row must be committed before the pipeline thread looks it up.
	 *
	 * @throws ServiceBusyException if the analysis queue is full
	 */
	public CreateAnalysisResponse createAnalysis(CreateAnalysisRequest request) {
		Analysis analysis = analysisRepository.save(Analysis.queued(request.repositoryUrl()));
		log.info("Analysis {} created with status {} for {}",
				analysis.getId(), analysis.getStatus(), analysis.getRepositoryUrl());

		try {
			analysisPipeline.run(analysis.getId());
		}
		catch (TaskRejectedException ex) {
			analysis.markFailed("The server is busy; the analysis queue is full");
			analysisRepository.save(analysis);
			throw new ServiceBusyException("Too many analyses are running. Please try again later.");
		}
		return new CreateAnalysisResponse(analysis.getId().toString(), analysis.getStatus());
	}

	/**
	 * Reads the current status and progress of an analysis from PostgreSQL, plus the failure reason when it FAILED.
	 *
	 * @param analysisId the id as it appears in the URL; anything that is not a UUID cannot exist
	 * @throws AnalysisNotFoundException if the id is malformed or no such analysis exists
	 */
	public AnalysisStatusResponse getStatus(String analysisId) {
		return analysisRepository.findById(parseId(analysisId))
				.map(AnalysisStatusResponse::from)
				.orElseThrow(AnalysisService::notFound);
	}

	/**
	 * Checks that an analysis exists and has finished successfully, for endpoints that serve its results.
	 *
	 * @return the analysis id, which is also the {@code analysisId} of its graph
	 * @throws AnalysisNotFoundException     if the id is malformed or no such analysis exists
	 * @throws AnalysisNotCompletedException if the analysis is still running or FAILED
	 */
	public UUID requireCompleted(String analysisId) {
		Analysis analysis = analysisRepository.findById(parseId(analysisId)).orElseThrow(AnalysisService::notFound);
		return switch (analysis.getStatus()) {
			case COMPLETED -> analysis.getId();
			case FAILED -> throw new AnalysisNotCompletedException("The analysis failed, so it has no results");
			default -> throw new AnalysisNotCompletedException(
					"The analysis has not completed yet (status: " + analysis.getStatus() + ")");
		};
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
}
