package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.abhiraj.archyra.common.AnalysisFailureException;
import com.abhiraj.archyra.graph.GraphBuilder;
import com.abhiraj.archyra.ingestion.DiscoveredFiles;
import com.abhiraj.archyra.ingestion.IngestionResult;
import com.abhiraj.archyra.ingestion.IngestionService;
import com.abhiraj.archyra.ingestion.SourceFile;
import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.RepositoryIR;
import com.abhiraj.archyra.parsing.ParseResult;
import com.abhiraj.archyra.parsing.ParsingService;
import com.abhiraj.archyra.parsing.ReferenceResolver;
import com.abhiraj.archyra.technology.TechnologyDetector;
import com.abhiraj.archyra.ir.TechnologyCategory;
import com.abhiraj.archyra.ir.TechnologyIR;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalysisPipelineTest {

	private static final String URL = "https://github.com/owner/repo";

	@Mock
	AnalysisRepository analysisRepository;
	@Mock
	IngestionService ingestionService;
	@Mock
	ParsingService parsingService;
	@Mock
	ReferenceResolver referenceResolver;
	@Mock
	TechnologyDetector technologyDetector;
	@Mock
	AnalysisMetadataRepository metadataRepository;
	@Mock
	GraphBuilder graphBuilder;
	@Mock
	AnalysisCleanup cleanup;
	@Mock
	com.abhiraj.archyra.usage.UsageService usageService;
	private AnalysisRegistrar registrar; // real, on the mocked repository: it writes FAILED and asks for the refund

	private final UUID id = UUID.randomUUID();
	private Analysis analysis;
	private final List<AnalysisStatus> savedStatuses = new ArrayList<>();
	private final List<Integer> savedProgress = new ArrayList<>();
	private AnalysisPipeline pipeline;
	/** Every event the pipeline publishes, with the analysis status at that moment. */
	private final List<String> events = new ArrayList<>();
	private java.util.function.Consumer<Object> eventListener = event -> { };

	private final RepositoryIR repositoryIr = new RepositoryIR("repo", URL, List.of());
	private final CodeGraphIR graph = new CodeGraphIR("repo", List.of(), List.of());

	@BeforeEach
	void setUp() {
		analysis = Analysis.queued(URL, java.util.UUID.randomUUID());
		ReflectionTestUtils.setField(analysis, "id", id);
		when(analysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		// Record the state at every save, because the same entity instance is mutated in place.
		when(analysisRepository.save(any(Analysis.class))).thenAnswer(invocation -> {
			Analysis saved = invocation.getArgument(0);
			savedStatuses.add(saved.getStatus());
			savedProgress.add(saved.getProgress());
			return saved;
		});

		IngestionResult ingestion = new IngestionResult("repo", Path.of("repo"), new DiscoveredFiles(
				List.of(new SourceFile("A.java", Path.of("A.java"), Language.JAVA)), Map.of(Language.JAVA, 1), 1));
		when(ingestionService.ingest(eq(URL), any(Path.class))).thenReturn(ingestion);
		when(parsingService.parse(eq("repo"), eq(URL), any()))
				.thenReturn(new ParseResult(repositoryIr, List.of(), 0));
		when(referenceResolver.resolve(repositoryIr)).thenReturn(graph);

		registrar = new AnalysisRegistrar(analysisRepository, usageService);
		pipeline = new AnalysisPipeline(analysisRepository, ingestionService, parsingService, referenceResolver,
				technologyDetector, metadataRepository, graphBuilder, registrar, cleanup, event -> {
					events.add(event.getClass().getSimpleName() + "@" + analysis.getStatus());
					eventListener.accept(event);
				});
	}

	@Test
	void advancesThroughEveryStageAndCompletes() {
		pipeline.run(id);

		assertThat(savedStatuses).containsExactly(AnalysisStatus.CLONING, AnalysisStatus.PARSING,
				AnalysisStatus.ANALYZING, AnalysisStatus.BUILDING_GRAPH, AnalysisStatus.COMPLETED);
		assertThat(savedProgress).containsExactly(5, 25, 60, 80, 100);
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getFailureReason()).isNull();
		verify(graphBuilder).build(id.toString(), graph);
		verify(graphBuilder, never()).delete(anyString());
	}

	@Test
	void announcesTheBuiltGraphWhileTheCloneExistsAndBeforeCompletionThenCompletion() {
		AtomicReference<Path> root = new AtomicReference<>();
		AtomicReference<Boolean> existedAtTheEvent = new AtomicReference<>();
		when(ingestionService.ingest(eq(URL), any(Path.class))).thenAnswer(invocation -> {
			Path dir = invocation.getArgument(1);
			Files.createDirectories(dir.resolve("repo"));
			return new IngestionResult("repo", dir.resolve("repo"), new DiscoveredFiles(List.of(), Map.of(), 0));
		});
		eventListener = event -> {
			if (event instanceof com.abhiraj.archyra.common.AnalysisGraphBuilt built) {
				root.set(built.root());
				existedAtTheEvent.set(Files.isDirectory(built.root()));
				assertThat(built.analysisId()).isEqualTo(id);
				assertThat(built.graph()).isSameAs(graph);
			}
		};

		pipeline.run(id);

		assertThat(events).containsExactly("AnalysisGraphBuilt@BUILDING_GRAPH", "AnalysisCompleted@COMPLETED");
		assertThat(existedAtTheEvent.get()).isTrue();
		assertThat(root.get()).doesNotExist(); // deleted afterwards, as always
	}

	@Test
	void aListenerThatThrowsNeverFailsTheAnalysis() {
		eventListener = event -> {
			throw new IllegalStateException("listener exploded");
		};

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getFailureReason()).isNull();
		assertThat(events).hasSize(2); // both events were still published
	}

	@Test
	void aFailedAnalysisAnnouncesTheFailureAndNoCompletion() {
		when(parsingService.parse(any(), any(), any())).thenThrow(new AnalysisFailureException("nothing parsable"));

		pipeline.run(id);

		assertThat(events).containsExactly("AnalysisFailed@FAILED");
	}

	@Test
	void failsWithTheReasonOfAnExpectedFailureAndKeepsTheStageProgress() {
		when(parsingService.parse(any(), any(), any()))
				.thenThrow(new AnalysisFailureException("No source files could be analysed"));

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).isEqualTo("No source files could be analysed");
		assertThat(analysis.getProgress()).isEqualTo(25);
		verify(referenceResolver, never()).resolve(any());
		verify(graphBuilder, never()).build(anyString(), any());
		verify(graphBuilder, never()).delete(anyString());
	}

	@Test
	void unexpectedErrorsGetAGenericReasonThatLeaksNoInternals() {
		when(ingestionService.ingest(any(), any())).thenThrow(new IllegalStateException("/secret/path exploded"));

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).isEqualTo("An unexpected error occurred while cloning the repository");
		assertThat(analysis.getFailureReason()).doesNotContain("secret");
	}

	@Test
	void anErrorThatIsNotAnExceptionStillEndsInFailed() {
		when(referenceResolver.resolve(any())).thenThrow(new StackOverflowError());

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).contains("analyzing the code");
	}

	@Test
	void aFailedGraphBuildRemovesThePartialGraph() {
		doThrow(new IllegalStateException("neo4j down")).when(graphBuilder).build(anyString(), any());

		pipeline.run(id);

		verify(graphBuilder).delete(id.toString());
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).isEqualTo("An unexpected error occurred while building the graph");
		assertThat(analysis.getProgress()).isEqualTo(80);
	}

	@Test
	void stillFailsTheAnalysisWhenCleaningUpThePartialGraphAlsoFails() {
		doThrow(new IllegalStateException("neo4j down")).when(graphBuilder).build(anyString(), any());
		doThrow(new IllegalStateException("still down")).when(graphBuilder).delete(anyString());

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
	}

	@Test
	void deletesTheTemporaryDirectoryAfterSuccess() {
		AtomicReference<Path> workDir = new AtomicReference<>();
		when(ingestionService.ingest(eq(URL), any(Path.class))).thenAnswer(invocation -> {
			Path dir = invocation.getArgument(1);
			Files.createDirectories(dir.resolve("repo"));
			Files.writeString(dir.resolve("repo/file.txt"), "x");
			workDir.set(dir);
			return new IngestionResult("repo", Path.of("repo"), new DiscoveredFiles(List.of(), Map.of(), 0));
		});

		pipeline.run(id);

		assertThat(workDir.get()).isNotNull().doesNotExist();
	}

	@Test
	void deletesTheTemporaryDirectoryAfterFailure() {
		AtomicReference<Path> workDir = new AtomicReference<>();
		when(ingestionService.ingest(eq(URL), any(Path.class))).thenAnswer(invocation -> {
			Path dir = invocation.getArgument(1);
			Files.writeString(dir.resolve("partial-clone.txt"), "x");
			workDir.set(dir);
			throw new AnalysisFailureException("The repository could not be cloned. Check that it exists and is public.");
		});

		pipeline.run(id);

		assertThat(workDir.get()).isNotNull().doesNotExist();
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
	}

	@Test
	void anUnknownAnalysisIsIgnored() {
		UUID unknown = UUID.randomUUID();
		when(analysisRepository.findById(unknown)).thenReturn(Optional.empty());

		pipeline.run(unknown);

		verify(ingestionService, never()).ingest(any(), any());
		verify(analysisRepository, never()).save(any());
	}

	@Test
	void survivesAFailureToWriteTheFailedStatus() {
		when(parsingService.parse(any(), any(), any())).thenThrow(new AnalysisFailureException("nothing to parse"));
		doAnswer(invocation -> {
			Analysis saved = invocation.getArgument(0);
			if (saved.getStatus() == AnalysisStatus.FAILED) {
				throw new IllegalStateException("postgres down");
			}
			return saved;
		}).when(analysisRepository).save(any(Analysis.class));

		pipeline.run(id); // must not throw
	}

	@Test
	void storesLanguagesWithAnalysedFlagAndDetectedTechnologies() {
		IngestionResult ingestion = new IngestionResult("repo", Path.of("repo"), new DiscoveredFiles(
				List.of(new SourceFile("A.java", Path.of("A.java"), Language.JAVA)),
				Map.of(Language.JAVA, 3, Language.SQL, 2), 6, List.of("pom.xml", "A.java")));
		when(ingestionService.ingest(eq(URL), any(Path.class))).thenReturn(ingestion);
		when(parsingService.canParse(Language.JAVA)).thenReturn(true);
		when(technologyDetector.detect(any(), any(), any(), any())).thenReturn(List.of(
				new TechnologyIR("Spring Boot", TechnologyCategory.BACKEND_FRAMEWORK, "maven", "pom.xml")));

		pipeline.run(id);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<AnalysisMetadata>> rows = ArgumentCaptor.forClass(List.class);
		verify(metadataRepository).saveAll(rows.capture());
		assertThat(rows.getValue()).extracting(AnalysisMetadata::getKind, AnalysisMetadata::getName,
				AnalysisMetadata::getFileCount, AnalysisMetadata::getAnalysed)
				.contains(org.assertj.core.groups.Tuple.tuple(MetadataKind.LANGUAGE, "Java", 3, true),
						org.assertj.core.groups.Tuple.tuple(MetadataKind.LANGUAGE, "SQL", 2, false),
						org.assertj.core.groups.Tuple.tuple(MetadataKind.TECHNOLOGY, "Spring Boot", null, null));
		assertThat(rows.getValue()).allMatch(row -> row.getAnalysisId().equals(id));
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
	}

	@Test
	void technologyDetectionFailuresNeverFailTheAnalysis() {
		when(technologyDetector.detect(any(), any(), any(), any())).thenThrow(new IllegalStateException("bad manifest"));

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		verify(graphBuilder).build(id.toString(), graph);
		verify(metadataRepository).saveAll(any());
	}

	@Test
	void detectsTechnologiesInTheClonedRepositoryFromAllItsFiles() {
		Path root = Path.of("clone-root");
		IngestionResult ingestion = new IngestionResult("repo", root, new DiscoveredFiles(
				List.of(new SourceFile("A.java", Path.of("A.java"), Language.JAVA)), Map.of(Language.JAVA, 1), 2,
				List.of("A.java", "Dockerfile")));
		when(ingestionService.ingest(eq(URL), any(Path.class))).thenReturn(ingestion);

		pipeline.run(id);

		verify(technologyDetector).detect(root, List.of("A.java", "Dockerfile"), repositoryIr, Map.of(Language.JAVA, 1));
	}

	// ---- quota ---------------------------------------------------------------------------------

	@Test
	void aFailedAnalysisGetsItsQuotaUnitBack() {
		UUID owner = analysis.getOwnerId();
		when(analysisRepository.markQuotaRefunded(id)).thenReturn(1);
		when(analysisRepository.findOwnerId(id)).thenReturn(Optional.of(owner));
		when(parsingService.parse(any(), any(), any())).thenThrow(new AnalysisFailureException("nothing to parse"));

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		verify(usageService).release(owner, com.abhiraj.archyra.usage.UsageType.REPOSITORY_ANALYSIS);
	}

	@Test
	void aCompletedAnalysisIsNeverRefunded() {
		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		verify(analysisRepository, never()).markQuotaRefunded(any());
		org.mockito.Mockito.verifyNoInteractions(usageService);
	}

	@Test
	void whenTheRefundFailsTheFailedStatusIsStillRecorded() {
		when(parsingService.parse(any(), any(), any())).thenThrow(new AnalysisFailureException("nothing to parse"));
		when(analysisRepository.markQuotaRefunded(id)).thenThrow(new IllegalStateException("postgres hiccup"));

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getFailureReason()).isEqualTo("nothing to parse");
	}

	// ---- re-runs -------------------------------------------------------------------------------

	@Test
	void aCompletedAnalysisAsksToReplaceItsPredecessorAfterItIsCompleted() {
		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		verify(cleanup).deleteReplaced(analysis);
	}

	@Test
	void aFailedAnalysisNeverReplacesAnything() {
		when(parsingService.parse(any(), any(), any())).thenThrow(new AnalysisFailureException("nothing to parse"));

		pipeline.run(id);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		verify(cleanup, never()).deleteReplaced(any());
	}
}
