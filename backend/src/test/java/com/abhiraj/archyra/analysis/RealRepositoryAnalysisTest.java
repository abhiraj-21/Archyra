package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;

import com.abhiraj.archyra.TestcontainersConfiguration;

/**
 * Analyses real public repositories in many languages with the real {@code git clone}. Needs network
 * access: {@code ARCHYRA_NETWORK_TESTS=true ./mvnw test -Dtest=RealRepositoryAnalysisTest}. Each run prints
 * a one-line summary of the graph.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ARCHYRA_NETWORK_TESTS", matches = "true")
class RealRepositoryAnalysisTest {

	@Autowired
	AnalysisService analysisService;
	@Autowired
	AnalysisRepository analysisRepository;
	@Autowired
	AnalysisMetadataRepository metadataRepository;
	@Autowired
	Neo4jClient neo4jClient;

	/** Repository, a language that must be analysed, and a technology that must be detected (null: none). */
	static Stream<Arguments> repositories() {
		return Stream.of(
				Arguments.of("https://github.com/spring-projects/spring-petclinic", "Java", "Spring Boot"),
				Arguments.of("https://github.com/psf/requests", "Python", null),
				Arguments.of("https://github.com/expressjs/express", "JavaScript", null),
				Arguments.of("https://github.com/typicode/lowdb", "TypeScript", null),
				Arguments.of("https://github.com/gin-gonic/gin", "Go", "Gin"),
				Arguments.of("https://github.com/sharkdp/fd", "Rust", "Cargo"),
				Arguments.of("https://github.com/jbogard/MediatR", "C#", ".NET SDK"),
				Arguments.of("https://github.com/slimphp/Slim", "PHP", "Composer"),
				Arguments.of("https://github.com/sinatra/sinatra", "Ruby", "Bundler"),
				Arguments.of("https://github.com/cashapp/turbine", "Kotlin", "Gradle"),
				Arguments.of("https://github.com/Uniswap/v2-core", "Solidity", null),
				Arguments.of("https://github.com/gothinkster/vue-realworld-example-app", "Vue", "Vue"),
				Arguments.of("https://github.com/sveltejs/realworld", "Svelte", "Svelte"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("repositories")
	void analysesARealRepository(String url, String language, String technology) {
		long start = System.currentTimeMillis();
		UUID id = UUID.fromString(analysisService.createAnalysis(new CreateAnalysisRequest(url)).analysisId());

		await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(1))
				.until(() -> analysisRepository.findById(id).map(Analysis::isFinished).orElse(false));
		Analysis analysis = analysisRepository.findById(id).orElseThrow();
		long seconds = (System.currentTimeMillis() - start) / 1000;

		List<AnalysisMetadata> metadata = metadataRepository.findByAnalysisId(id);
		String nodes = neo4jClient.query("MATCH (n {analysisId: $id}) WHERE n.external = false RETURN labels(n)[0] AS label, count(n) AS c ORDER BY label")
				.bind(id.toString()).to("id")
				.fetchAs(String.class).mappedBy((ts, r) -> r.get("label").asString() + "=" + r.get("c").asLong()).all().toString();
		String edges = neo4jClient.query("MATCH (a {analysisId: $id})-[r]->(b) RETURN type(r) AS type, count(r) AS c ORDER BY type")
				.bind(id.toString()).to("id")
				.fetchAs(String.class).mappedBy((ts, r) -> r.get("type").asString() + "=" + r.get("c").asLong()).all().toString();
		String technologies = metadata.stream().filter(row -> row.getKind() == MetadataKind.TECHNOLOGY)
				.map(AnalysisMetadata::getName).toList().toString();
		String languages = metadata.stream().filter(row -> row.getKind() == MetadataKind.LANGUAGE)
				.map(row -> row.getName() + "=" + row.getFileCount() + (Boolean.TRUE.equals(row.getAnalysed()) ? "" : "*")).toList().toString();
		System.out.println("REAL " + url + " (" + seconds + "s) " + analysis.getStatus()
				+ (analysis.getFailureReason() != null ? " reason=" + analysis.getFailureReason() : "")
				+ "\n  languages " + languages + "\n  nodes " + nodes + "\n  edges " + edges + "\n  technologies " + technologies);

		assertThat(analysis.getFailureReason()).isNull();
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(metadata).anyMatch(row -> row.getKind() == MetadataKind.LANGUAGE && row.getName().equals(language)
				&& Boolean.TRUE.equals(row.getAnalysed()));
		if (technology != null) {
			assertThat(metadata).anyMatch(row -> row.getKind() == MetadataKind.TECHNOLOGY && row.getName().equals(technology));
		}
		assertThat(count(id, "MATCH (n {analysisId: $id}) WHERE n.external = false AND labels(n)[0] IN ['Class', 'Interface', 'Function', 'Method'] RETURN count(n) AS c"))
				.as("declarations found").isPositive();
	}

	private long count(UUID id, String cypher) {
		return neo4jClient.query(cypher).bind(id.toString()).to("id")
				.fetchAs(Long.class).mappedBy((ts, r) -> r.get("c").asLong()).one().orElse(0L);
	}
}
