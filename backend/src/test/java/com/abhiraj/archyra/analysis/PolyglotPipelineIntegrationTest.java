package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.ingestion.GitCloner;

/**
 * One repository in five languages, with manifests, containers and CI, through the whole pipeline against real
 * PostgreSQL and Neo4j. The mocked clone assembles the repository from the per-language fixtures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PolyglotPipelineIntegrationTest {

	private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
	private static final String URL = "https://github.com/example/monorepo";

	@Autowired
	AnalysisService analysisService;
	@Autowired
	AnalysisRepository analysisRepository;
	@Autowired
	AnalysisMetadataRepository metadataRepository;
	@Autowired
	Neo4jClient neo4jClient;

	@MockitoBean
	GitCloner gitCloner;

	@BeforeEach
	void cloneAMixedRepository() {
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1);
			copy("polyglot-extras", target);
			copy("sample-repo", target.resolve("shop"));
			copy("polyglot/python", target.resolve("api"));
			copy("polyglot/typescript", target.resolve("web"));
			copy("polyglot/go", target.resolve("go-service"));
			copy("polyglot/solidity", target.resolve("contracts"));
			Files.writeString(target.resolve("api/broken.py"), "def (:\n    class\n");
			return null;
		}).when(gitCloner).clone(anyString(), any(Path.class));
	}

	private static void copy(String fixture, Path target) throws java.io.IOException {
		FileSystemUtils.copyRecursively(FIXTURES.resolve(fixture), target);
	}

	private UUID analyse() {
		UUID id = UUID.fromString(analysisService.createAnalysis(new CreateAnalysisRequest(URL)).analysisId());
		await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(250))
				.until(() -> analysisRepository.findById(id).map(Analysis::isFinished).orElse(false));
		return id;
	}

	private List<AnalysisMetadata> metadata(UUID id, MetadataKind kind) {
		return metadataRepository.findByAnalysisId(id).stream().filter(row -> row.getKind() == kind).toList();
	}

	@Test
	void analysesAMixedRepositoryEvenWithABrokenFile() {
		UUID id = analyse();

		Analysis analysis = analysisRepository.findById(id).orElseThrow();
		assertThat(analysis.getFailureReason()).isNull();
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getProgress()).isEqualTo(100);
	}

	@Test
	void everyLanguageContributesItsOwnClassesToTheGraph() {
		String id = analyse().toString();

		assertThat(exists(id, "Class", "class:com.example.shop.web.OrderController")).as("Java").isTrue();
		assertThat(exists(id, "Class", "class:api.app.services.user_service.UserService")).as("Python").isTrue();
		assertThat(exists(id, "Class", "class:web.src.services.user.service.UserService")).as("TypeScript").isTrue();
		assertThat(exists(id, "Class", "class:go-service.internal.store.Server")).as("Go").isTrue();
		assertThat(exists(id, "Class", "class:contracts.contracts.Token.Token")).as("Solidity").isTrue();
		assertThat(exists(id, "Function", "function:api/main.py#main()")).as("Python function").isTrue();
		assertThat(exists(id, "Function", "function:web/src/app.ts#main(any)")).as("TypeScript function").isTrue();

		List<String> languagesInGraph = neo4jClient.query("MATCH (f:File {analysisId: $id}) RETURN DISTINCT f.kind AS kind")
				.bind(id).to("id").fetchAs(String.class).mappedBy((ts, r) -> r.get("kind").asString()).all().stream().toList();
		assertThat(languagesInGraph).contains("JAVA", "PYTHON", "TYPESCRIPT", "GO", "SOLIDITY");
	}

	@Test
	void relationshipsAreResolvedWithinEachLanguageAndNeverAcrossThem() {
		String id = analyse().toString();

		assertThat(edge(id, "Class", "class:api.app.services.user_service.UserService", "DEPENDS_ON", "Class", "class:api.app.models.user.User")).isTrue();
		assertThat(edge(id, "Method", "method:web.src.services.user.service.UserService#load(string)", "CALLS", "Method",
				"method:web.src.services.user.repository.UserRepository#find(string)")).isTrue();
		assertThat(edge(id, "Method", "method:go-service.internal.store.Server#Start()", "CALLS", "Function",
				"function:go-service/internal/model/user.go#NewUser(string)")).isTrue();
		assertThat(edge(id, "Class", "class:contracts.contracts.Token.Token", "EXTENDS", "Class", "class:contracts.contracts.Base.Base")).isTrue();
		assertThat(edge(id, "Class", "class:com.example.shop.web.OrderController", "DEPENDS_ON", "Interface",
				"interface:com.example.shop.service.OrderService")).isTrue();
		assertThat(edge(id, "File", "file:api/app/services/user_service.py", "IMPORTS", "Module", "external:module:numpy")).isTrue();

		long crossLanguage = neo4jClient.query("""
				MATCH (a:File {analysisId: $id})-[:IMPORTS]->(b:File {analysisId: $id})
				WHERE a.kind <> b.kind RETURN count(*) AS c""")
				.bind(id).to("id").fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(-1L);
		assertThat(crossLanguage).isZero();
	}

	@Test
	void languagesAreStoredWithFileCountsAndWhetherTheyAreAnalysed() {
		UUID id = analyse();

		List<AnalysisMetadata> languages = metadata(id, MetadataKind.LANGUAGE);
		assertThat(languages).extracting(AnalysisMetadata::getName, AnalysisMetadata::getAnalysed)
				.contains(org.assertj.core.groups.Tuple.tuple("Java", true), org.assertj.core.groups.Tuple.tuple("Python", true),
						org.assertj.core.groups.Tuple.tuple("TypeScript", true), org.assertj.core.groups.Tuple.tuple("Go", true),
						org.assertj.core.groups.Tuple.tuple("Solidity", true), org.assertj.core.groups.Tuple.tuple("SQL", false),
						org.assertj.core.groups.Tuple.tuple("Dockerfile", false));
		assertThat(languages.stream().filter(l -> l.getName().equals("Java")).findFirst().orElseThrow().getFileCount()).isEqualTo(9);
	}

	@Test
	void technologiesAreStoredWithCategoryAndEvidence() {
		UUID id = analyse();

		List<AnalysisMetadata> technologies = metadata(id, MetadataKind.TECHNOLOGY);
		assertThat(technologies).extracting(AnalysisMetadata::getName, AnalysisMetadata::getCategory, AnalysisMetadata::getEvidence)
				.contains(org.assertj.core.groups.Tuple.tuple("React", "FRONTEND_FRAMEWORK", "package.json"),
						org.assertj.core.groups.Tuple.tuple("Express", "BACKEND_FRAMEWORK", "package.json"),
						org.assertj.core.groups.Tuple.tuple("FastAPI", "BACKEND_FRAMEWORK", "requirements.txt"),
						org.assertj.core.groups.Tuple.tuple("PyTorch", "AI_ML", "requirements.txt"),
						org.assertj.core.groups.Tuple.tuple("Docker", "INFRASTRUCTURE", "Dockerfile"),
						org.assertj.core.groups.Tuple.tuple("PostgreSQL", "DATABASE", "docker-compose.yml"),
						org.assertj.core.groups.Tuple.tuple("GitHub Actions", "CI_CD", ".github/workflows/ci.yml"),
						org.assertj.core.groups.Tuple.tuple("Hardhat", "BLOCKCHAIN", "hardhat.config.js"),
						org.assertj.core.groups.Tuple.tuple("ethers.js", "BLOCKCHAIN", "package.json"));
		assertThat(technologies).extracting(AnalysisMetadata::getName).doesNotHaveDuplicates();
	}

	private boolean exists(String analysisId, String label, String nodeId) {
		return neo4jClient.query("MATCH (n:" + label + " {analysisId: $id, nodeId: $nodeId}) RETURN count(n) AS c")
				.bind(analysisId).to("id").bind(nodeId).to("nodeId")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(0L) > 0;
	}

	private boolean edge(String analysisId, String sourceLabel, String sourceId, String type, String targetLabel, String targetId) {
		return neo4jClient.query("MATCH (a:" + sourceLabel + " {analysisId: $id, nodeId: $source})-[r:" + type + "]->(b:"
						+ targetLabel + " {analysisId: $id, nodeId: $target}) RETURN count(r) AS c")
				.bind(analysisId).to("id").bind(sourceId).to("source").bind(targetId).to("target")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(0L) > 0;
	}
}
