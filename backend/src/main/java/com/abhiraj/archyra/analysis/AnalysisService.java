package com.abhiraj.archyra.analysis;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

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
}
