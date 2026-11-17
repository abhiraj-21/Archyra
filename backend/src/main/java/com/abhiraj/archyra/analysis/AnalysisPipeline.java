package com.abhiraj.archyra.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.common.AsyncConfig;
import com.abhiraj.archyra.graph.GraphBuilder;
import com.abhiraj.archyra.ingestion.IngestionResult;
import com.abhiraj.archyra.ingestion.IngestionService;
import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.RepositoryIR;
import com.abhiraj.archyra.ir.TechnologyIR;
import com.abhiraj.archyra.technology.TechnologyDetector;
import com.abhiraj.archyra.parsing.ParseResult;
import com.abhiraj.archyra.parsing.ParsingService;
import com.abhiraj.archyra.parsing.ReferenceResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs one analysis in the background:
 * clone and discover files, parse, resolve references, build the Neo4j graph.
 *
 * <p>The status and progress in PostgreSQL follow the stages. Whatever goes wrong, the analysis ends as
 * {@code FAILED} with a reason; it is never left in an intermediate status. No database transaction is held
 * while the pipeline runs: every status change is its own short write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisPipeline {

	private final AnalysisRepository analysisRepository;
	private final IngestionService ingestionService;
	private final ParsingService parsingService;
	private final ReferenceResolver referenceResolver;
	private final TechnologyDetector technologyDetector;
	private final AnalysisMetadataRepository metadataRepository;
	private final GraphBuilder graphBuilder;
	private final AnalysisRegistrar registrar;
	private final AnalysisCleanup cleanup;

	/** Starts the pipeline for an already persisted analysis on the analysis executor. */
	@Async(AsyncConfig.ANALYSIS_EXECUTOR)
	public void run(UUID analysisId) {
		Analysis analysis = analysisRepository.findById(analysisId).orElse(null);
		if (analysis == null) {
			log.error("Analysis {} does not exist; nothing to run", analysisId);
			return;
		}

		AnalysisStatus stage = analysis.getStatus();
		Path workDir = null;
		try {
			workDir = Files.createTempDirectory("archyra-analysis-");

			stage = advance(analysisId, AnalysisStatus.CLONING, 5);
			IngestionResult ingestion = ingestionService.ingest(analysis.getRepositoryUrl(), workDir);

			stage = advance(analysisId, AnalysisStatus.PARSING, 25);
			ParseResult parsed = parsingService.parse(
					ingestion.repositoryName(), analysis.getRepositoryUrl(), ingestion.files().sourceFiles());

			stage = advance(analysisId, AnalysisStatus.ANALYZING, 60);
			CodeGraphIR graph = referenceResolver.resolve(parsed.repository());
			List<TechnologyIR> technologies = detectTechnologies(analysisId, ingestion, parsed.repository());
			saveMetadata(analysisId, ingestion, technologies);

			stage = advance(analysisId, AnalysisStatus.BUILDING_GRAPH, 80);
			graphBuilder.build(analysisId.toString(), graph);

			update(analysisId, Analysis::markCompleted);
			log.info("Analysis {} completed for {}", analysisId, analysis.getRepositoryUrl());
			cleanup.deleteReplaced(analysis); // a re-run replaces its predecessor only now; never throws
		}
		catch (Throwable failure) { // an analysis must never stay stuck, whatever was thrown
			fail(analysisId, stage, failure);
		}
		finally {
			deleteWorkDir(workDir);
		}
	}

	/** Technology detection is best effort: whatever goes wrong, the analysis continues without it. */
	private List<TechnologyIR> detectTechnologies(UUID analysisId, IngestionResult ingestion, RepositoryIR repository) {
		try {
			List<TechnologyIR> technologies = technologyDetector.detect(ingestion.root(), ingestion.files().allPaths(),
					repository, ingestion.files().languageFiles());
			log.info("Analysis {} uses {} technologies", analysisId, technologies.size());
			return technologies;
		}
		catch (RuntimeException ex) {
			log.warn("Technology detection failed for analysis {}; continuing without it", analysisId, ex);
			return List.of();
		}
	}

	/** Languages with their file counts (whether analysed or only counted) and the detected technologies. */
	private void saveMetadata(UUID analysisId, IngestionResult ingestion, List<TechnologyIR> technologies) {
		List<AnalysisMetadata> rows = new ArrayList<>();
		ingestion.files().languageFiles().forEach((language, count) ->
				rows.add(AnalysisMetadata.language(analysisId, language, count, parsingService.canParse(language))));
		technologies.forEach(technology -> rows.add(AnalysisMetadata.technology(analysisId, technology)));
		metadataRepository.saveAll(rows);
	}

	private AnalysisStatus advance(UUID analysisId, AnalysisStatus status, int progress) {
		update(analysisId, analysis -> analysis.advanceTo(status, progress));
		return status;
	}

	private void update(UUID analysisId, Consumer<Analysis> change) {
		Analysis analysis = analysisRepository.findById(analysisId)
				.orElseThrow(() -> new IllegalStateException("Analysis " + analysisId + " disappeared"));
		change.accept(analysis);
		analysisRepository.save(analysis);
	}

	private void fail(UUID analysisId, AnalysisStatus stage, Throwable failure) {
		String reason;
		if (failure instanceof AnalysisFailureException) {
			reason = failure.getMessage();
			log.warn("Analysis {} failed while {}: {}", analysisId, describe(stage), reason);
		}
		else {
			// Internals stay in the log; the stored reason is safe to show to users.
			reason = "An unexpected error occurred while " + describe(stage);
			log.error("Analysis {} failed unexpectedly while {}", analysisId, describe(stage), failure);
		}

		if (stage == AnalysisStatus.BUILDING_GRAPH) {
			try {
				graphBuilder.delete(analysisId.toString()); // do not leave a half-written graph behind
			}
			catch (Throwable cleanupFailure) {
				log.error("Could not remove the partial graph of analysis {}", analysisId, cleanupFailure);
			}
		}

		// FAILED and the quota refund are one transaction, so a failed analysis never keeps its unit.
		try {
			registrar.failAndRefund(analysisId, reason);
			return;
		}
		catch (Throwable refundFailure) {
			log.error("Could not mark analysis {} as FAILED together with its quota refund; recording FAILED alone, "
					+ "startup repair returns the unit", analysisId, refundFailure);
		}
		try {
			String storedReason = reason;
			update(analysisId, analysis -> analysis.markFailed(storedReason));
		}
		catch (Throwable updateFailure) {
			log.error("Could not mark analysis {} as FAILED; startup recovery will fix it", analysisId, updateFailure);
		}
	}

	private static String describe(AnalysisStatus stage) {
		return switch (stage) {
			case QUEUED -> "starting the analysis";
			case CLONING -> "cloning the repository";
			case PARSING -> "parsing the source code";
			case ANALYZING -> "analyzing the code";
			case BUILDING_GRAPH -> "building the graph";
			case COMPLETED, FAILED -> "finishing the analysis";
		};
	}

	private static void deleteWorkDir(Path workDir) {
		if (workDir == null) {
			return;
		}
		try {
			FileSystemUtils.deleteRecursively(workDir);
		}
		catch (IOException ex) {
			log.warn("Could not delete the temporary directory {}", workDir, ex);
		}
	}
}
