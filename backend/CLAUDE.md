# backend/CLAUDE.md

Backend-specific guidance. System-wide architecture, database boundaries, API standards and the roadmap are in the root `CLAUDE.md` and `Architecture.md`.

## Stack

- **Java 25 (LTS)**, Spring Boot 4.0.x (parent `spring-boot-starter-parent` 4.0.9), Maven wrapper (Maven 3.9.16, configured in `.mvn/wrapper/maven-wrapper.properties`). A JDK 25 must be active (`JAVA_HOME`); the build fails on older JDKs.
- Current dependencies: `spring-boot-starter-webmvc`, `spring-boot-starter-validation` and `io.github.tree-sitter:jtreesitter` 0.27.0, plus the `-test` starters. Lombok is configured as an annotation processor in `maven-compiler-plugin` for both main and test compilation. A new annotation processor (such as MapStruct) needs adding to both `annotationProcessorPaths` blocks.
- Spring Boot 4 notes: the web starter is `spring-boot-starter-webmvc` (not `-web`), test starters are per technology (`*-test`), and Jackson 3 is the default JSON library (packages `tools.jackson.*`, annotations still in `com.fasterxml.jackson.annotation`). Check Boot 4 documentation rather than relying on Boot 3 habits.
- Config lives in `src/main/resources/application.properties`. Use `.properties`, not YAML, to stay consistent.

## Commands

Run from `backend/`:

```bash
./mvnw spring-boot:run                                   # run the app (port 8080)
./mvnw clean package                                     # compile, test, build jar
./mvnw clean package -DskipTests                         # build jar without tests
./mvnw test                                              # all tests
./mvnw test -Dtest=ArchyraApplicationTests               # single test class
./mvnw test -Dtest=ArchyraApplicationTests#contextLoads  # single test method
```

## Package conventions

The base package is `com.abhiraj.archyra`. Organize code by feature, not by layer. Each feature package holds its own controller, service, DTOs and persistence.

```
com.abhiraj.archyra
├── analysis/      # Analysis lifecycle: POST/GET /api/v1/analyses, status/progress, async orchestration
├── ingestion/     # Clone/download repository, file discovery, language detection
├── parsing/       # Tree-sitter integration, one extractor per language
├── ir/            # Archyra Intermediate Representation: plain language-agnostic model
├── graph/         # IR → Neo4j graph builder, graph queries, /graph and /nodes endpoints
├── codebase/      # Read endpoints: /repository, /files, /classes
└── common/        # Exception handling, error response, shared config
```

- Controllers stay thin. They validate input, call a service and map the result to a DTO. They never return JPA entities or Neo4j node entities directly.
- Use Java `record`s for request and response DTOs. Use Lombok for entities and services (`@Getter`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`). Avoid `@Data` on JPA or Neo4j entities, because it generates `equals`/`hashCode` from mutable state.
- Use constructor injection only (`@RequiredArgsConstructor` with `final` fields). No field `@Autowired`.
- Model enums from `Architecture.md` as Java enums whose names match the API values exactly: `AnalysisStatus`, `NodeType`, `RelationshipType`, and later `ExpertiseLevel`.

## V0.1 rules

**Spring Web**
- Routes are under `/api/v1`. Implement only the V0.1 endpoints from `Architecture.md` (`analyses`, `analyses/{id}`, `/repository`, `/graph`, `/nodes/{nodeId}`, `/files`, `/files/{fileId}`, `/classes/{classId}`).
- `POST /api/v1/analyses` must return `{ analysisId, status: "QUEUED" }` without waiting for the analysis. Run the pipeline in the background with `@Async` on a dedicated, bounded `ThreadPoolTaskExecutor`. Do not add Redis or RabbitMQ yet.
- Update the status and `progress` as the pipeline advances through `CLONING`, `PARSING`, `ANALYZING`, `BUILDING_GRAPH` and `COMPLETED`. Any failure sets `FAILED` with a reason; never leave an analysis stuck in an intermediate status.
- A single `@RestControllerAdvice` in `common/` maps exceptions to `{ code, message }` with the right HTTP status (400 for validation, 404 for an unknown analysis or node, 409 when results are requested before `COMPLETED`).

**Validation**
- Annotate request records with Jakarta Validation (`@NotBlank`, `@Pattern`) and use `@Valid` on controller parameters.
- `repositoryUrl` must be an `https://github.com/{owner}/{repo}` URL. Reject anything else before cloning, including `file://`, other hosts, and paths with `..`.
- Bound user-controlled query parameters. This matters now for filters and later for `depth` and `maxNodes`.

**Lombok**
- Use it for boilerplate only. Prefer records over Lombok `@Value` for DTOs.

**Ingestion**
- Clone into a per-analysis temporary directory (shallow clone, `--depth 1`) and always delete it afterwards, in `finally`, including on failure.
- Enforce limits on repository size and file count. Skip binaries, vendored code and build output (`node_modules`, `target`, `build`, `dist`, `.git`).

## Upcoming: Tree-sitter

- Tree-sitter belongs only in `parsing/`. Nothing outside that package imports Tree-sitter types. Parsers turn Tree-sitter ASTs into IR objects, and everything downstream (graph builder, API) depends only on the IR.
- Use one extractor per language behind a common interface, for example `LanguageExtractor { boolean supports(Language); FileIR extract(SourceFile); }`. Start with Java; adding a language must not touch the graph builder.
- The binding is the official `io.github.tree-sitter:jtreesitter` (0.27.0, built on the Foreign Function & Memory API). The reasoning is recorded in `Architecture.md` → Technologies → Notes. Do not switch to JNI bindings such as `tree-sitter-ng`. Upgrading jtreesitter may raise the minimum Java version, so check its `maven.compiler.release` first.
- jtreesitter ships no native code. The tree-sitter runtime library and each language grammar (for example `tree-sitter-java`) must be available as native shared libraries, loaded through `SymbolLookup` when creating a `Language`. Keep all native loading in one place in `parsing/`, and plan how these libraries get packaged for tests, local runs and deployment.
- Run the JVM with `--enable-native-access=ALL-UNNAMED` (Surefire `argLine` and `spring-boot-maven-plugin` `jvmArguments`) once parsing code first runs, to avoid native-access warnings and keep working when they become errors.
- Prefer Tree-sitter queries (`.scm` files under `src/main/resources/queries/{language}/`) over hand-written AST walking where practical.
- Resolve references (calls, type usages) in a second pass over the IR of the whole repository, after every file has been parsed. Mark unresolved targets as external rather than dropping them.
- Parsing one bad file must not fail the analysis. Log it, record it and continue.

## Upcoming: Neo4j

- Add `spring-boot-starter-data-neo4j`. Graph code lives in `graph/`.
- Every node carries `analysisId`, and every query filters on it. Graphs from different analyses never connect.
- Node labels and relationship types must match the model in the root `CLAUDE.md` exactly. Add new labels or types to `Architecture.md` first.
- Give nodes stable IDs that the API exposes as `nodeId`, derived from the analysis plus a qualified name (for example `class:com.foo.UserService`). Do not expose Neo4j internal IDs.
- Write the graph in batches (`UNWIND $rows ... MERGE`) rather than saving node by node. For traversals, use `Neo4jClient` or `@Query` Cypher rather than loading large object graphs through repositories.
- Map query results into the React Flow `{ nodes, edges }` DTO in `graph/`. The same mapper will serve the V2 parameters (`rootNodeId`, `depth`, `maxNodes`), so keep it parameter-driven.
- PostgreSQL (Spring Data JPA) holds only analysis metadata. Code entities never go into Postgres.

## Testing

- `ArchyraApplicationTests#contextLoads` is the only test. Once Neo4j or Postgres are added, it needs those services; use Testcontainers (`spring-boot-testcontainers` with `@ServiceConnection`) rather than requiring local installs.
- Test parsers and extractors as plain unit tests against small fixture source files under `src/test/resources/fixtures/`, without a Spring context.
- Use `@WebMvcTest` slices for controllers.
