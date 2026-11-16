# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. It covers `backend/`.

Backend-specific guidance. System-wide architecture, database boundaries, API standards and the roadmap are in the root `CLAUDE.md` and `Architecture.md`.

## Stack

- **Java 25 (LTS)**, Spring Boot 4.0.x (parent `spring-boot-starter-parent` 4.0.9), Maven wrapper (Maven 3.9.16, configured in `.mvn/wrapper/maven-wrapper.properties`). A JDK 25 must be active (`JAVA_HOME`); the build fails on older JDKs.
- Current dependencies: `spring-boot-starter-webmvc`, `-validation`, `-data-jpa`, `-data-neo4j`, the PostgreSQL driver and `io.github.tree-sitter:jtreesitter` 0.27.0, plus the `-test` starters and Testcontainers (`spring-boot-testcontainers`, PostgreSQL and Neo4j modules). Lombok is configured as an annotation processor in `maven-compiler-plugin` for both main and test compilation. A new annotation processor (such as MapStruct) needs adding to both `annotationProcessorPaths` blocks.
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
./scripts/build-native.sh                                # build the Tree-sitter runtime and all grammars into native/
GRAMMARS="python go" ./scripts/build-native.sh           # build only some grammars
ARCHYRA_NETWORK_TESTS=true ./mvnw test -Dtest='GitClonerTest,RealRepositoryAnalysisTest'  # opt-in tests that use GitHub
```

### Running locally

- **Native libraries:** run `./scripts/build-native.sh` once (needs git, make, a C compiler and a C++ compiler for Ruby's scanner). It builds `libtree-sitter` and one `libtree-sitter-<language>` per grammar listed in the script into `backend/native/` (git-ignored). The directory is `archyra.parsing.native-library-dir` (`NATIVE_LIB_DIR`). Tests that run the real parser and the pipeline integration test need it.
- **Services:** PostgreSQL and Neo4j. Tests start their own through Testcontainers (Docker required); `spring-boot:run` needs real ones, for example `docker run -d -p 7687:7687 -e NEO4J_AUTH=neo4j/<password> neo4j:5.26-community`.
- **`.env` (git-ignored, in `backend/`):** `DB_USERNAME`, `DB_PASSWORD`, `NEO4J_PASSWORD` are required. Optional: `NEO4J_URI`, `NEO4J_USERNAME`, `NATIVE_LIB_DIR`. Run the app from `backend/`, since `.env` and `native/` are resolved against the working directory.

## Package conventions

The base package is `com.abhiraj.archyra`. Organize code by feature, not by layer (package-by-feature). Each feature package holds its own controller, service, DTOs and persistence.

- Only feature packages and `common/` sit directly under `com.abhiraj.archyra`, alongside `ArchyraApplication`. Never create root-level layer packages such as `controller/`, `service/`, `repository/`, `dto/` or `entity/`.
- Inside a feature package, keep related classes flat. Add a subpackage only when a feature grows large enough to need one, for example `analysis/internal/` for classes no other feature should use.
- Put a class in the feature that owns its use case, not the one that happens to call it. Shared code moves to `common/` only when two or more features need it, and never into a generic `util/` or `helpers/` package.
- Features talk to each other through their public services and IR/DTO types. Do not reach into another feature's repositories or internal classes.
- New features get a new top-level package, and the tree below is updated in the same change.

```
com.abhiraj.archyra
├── analysis/      # Analysis lifecycle: POST/GET /api/v1/analyses, status/progress, the background AnalysisPipeline
├── ingestion/     # Clone/download repository, file discovery, language detection
├── parsing/       # TreeSitterExtractor base, ParsingService, ReferenceResolver (IR → code graph IR)
│   └── lang/      # one extractor (and, where imports differ, one ModuleResolver) per language
├── technology/    # Frameworks, libraries and tools: technologies.tsv catalog, manifest reader, detector
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
- **Implemented:** every V0.1 endpoint: `POST /analyses`, `GET /analyses/{id}`, `/repository`, `/graph`, `/nodes/{nodeId}`, `/files`, `/files/{fileId}` and `/classes/{classId}`. Per-endpoint behaviour (fields, filters, edge cases) is specified in `Architecture.md`, next to each endpoint; how they are wired is in "Read endpoints" below.
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

## Tree-sitter rules

- Tree-sitter belongs only in `parsing/`. Nothing outside that package imports Tree-sitter types. Parsers turn Tree-sitter ASTs into IR objects, and everything downstream (graph builder, API) depends only on the IR.
- One extractor per language behind `LanguageExtractor { boolean supports(Language); FileIR extract(SourceFile); }`, in practice by extending `TreeSitterExtractor`. Adding a language must not touch the graph builder or the resolver (see "Languages and technologies").
- The binding is the official `io.github.tree-sitter:jtreesitter` (0.27.0, built on the Foreign Function & Memory API). The reasoning is recorded in `Architecture.md` → Technologies → Notes. Do not switch to JNI bindings such as `tree-sitter-ng`. Upgrading jtreesitter may raise the minimum Java version, so check its `maven.compiler.release` first.
- jtreesitter ships no native code. The runtime and each grammar are native shared libraries built by `scripts/build-native.sh` into `native/` (`archyra.parsing.native-library-dir`) and loaded only by `parsing/NativeLibraries`, which is also registered as jtreesitter's `NativeLibraryLookup` service. Packaging them for deployment (a Docker image that runs the script) is still to do.
- The JVM runs with `--enable-native-access=ALL-UNNAMED`, already set for Surefire (`argLine`) and `spring-boot:run` (`jvmArguments`) in `pom.xml`. Any other launcher (a jar, a Docker entrypoint) needs the same flag.
- Extractors currently walk the syntax tree through the `TreeSitterExtractor` hooks rather than using `.scm` queries; keep new extractors consistent with that. Check node and field names with a real parse (print the tree) instead of guessing: grammars name them differently.
- References (calls, type usages, imports) are resolved in `ReferenceResolver`, a second pass over the IR of the whole repository after every file is parsed. Targets outside the repository become `external` nodes; a reference tied to nothing known (an un-imported global, an untyped receiver) is counted and skipped, not guessed.
- Parsing one bad file must not fail the analysis. Log it, record it and continue.

## Neo4j rules

- Graph code lives in `graph/`. It uses `Neo4jClient` and Cypher only; Spring Data Neo4j repositories are switched off (`spring.data.neo4j.repositories.type=none`) so they do not scan the JPA repositories.
- Every node carries `analysisId`, and every query filters on it. Graphs from different analyses never connect.
- Node labels and relationship types must match the model in the root `CLAUDE.md` exactly. Add new labels or types to `Architecture.md` first.
- Give nodes stable IDs that the API exposes as `nodeId`, derived from the analysis plus a qualified name (for example `class:com.foo.UserService`). Do not expose Neo4j internal IDs.
- Write the graph in batches (`UNWIND $rows ... MERGE`) rather than saving node by node. For traversals, use `Neo4jClient` or `@Query` Cypher rather than loading large object graphs through repositories.
- Map query results into the React Flow `{ nodes, edges }` DTO in `graph/`. The same mapper will serve the V2 parameters (`rootNodeId`, `depth`, `maxNodes`), so keep it parameter-driven.
- PostgreSQL (Spring Data JPA) holds only analysis metadata. Code entities never go into Postgres.

## Languages and technologies (implemented)

The pipeline is language-neutral; only extractors and module resolvers know a language.

| Level | Languages |
| --- | --- |
| Analysed (classes, functions, imports, calls) | Java, Kotlin, Python, JavaScript/JSX, TypeScript/TSX, Go, Rust, C#, PHP, Ruby, Solidity, Vue and Svelte components (their `<script>`), Jupyter notebooks (code cells) |
| Detected and counted only | Swift, Dart, C, C++, Scala, R, Julia, shell, SQL, HCL, Dockerfile, HTML, CSS |

- **Extractors** (`parsing/lang/`) extend `TreeSitterExtractor`, which owns parsing, nesting, qualified names and iterative body scanning. A language implements only the hooks that exist in it (`typeOf`, `functionOf`, `importsOf`, `callOf`, ...); a language without classes never returns a `TypeDecl`. Do not force a language's concepts into another's: free functions are `Function` nodes, never fake classes.
- **IR contract:** a file has a `moduleName` (what qualifies its symbols and what imports refer to: Java package, Python/JS module path, Go package directory) and a `packageName` (the Module node grouping). Imports are language-neutral bindings (`ImportIR`: symbol, module or wildcard import). A type is `FULL`, `PARTIAL` (merged: C# `partial`, Ruby open classes) or `EXTENSION` (Go receiver methods, Rust `impl`).
- **Module resolvers** map an import specifier to a module of the repository (Python dotted and relative names, JS paths and barrels, Go directories, Rust `crate`/`super`, Ruby `require`, Solidity paths). `ReferenceResolver` itself has no language rules.
- **To add a language:** (1) a row in `GRAMMARS_TABLE` of `scripts/build-native.sh`; (2) the `Language` value and its extensions in `ingestion/LanguageDetector`; (3) an extractor `@Component` in `parsing/lang/`; (4) a `ModuleResolver` only if its import semantics differ from "the import names the module"; (5) a fixture repository under `src/test/resources/fixtures/polyglot/<language>/`, a test built on `FixtureAnalysis`, and the new classes added to the test helper `Languages`; (6) rows in `src/main/resources/technologies.tsv` for its frameworks.
- **Technologies** are detected from files (Dockerfile, `*.tf`, CI, Kubernetes, `hardhat.config.*`, `AndroidManifest.xml`, ...), manifests (`package.json`, `pom.xml`, `requirements.txt`, `go.mod`, `Cargo.toml`, ...), framework languages (Vue, Svelte) and imports. They are best effort and can never fail an analysis. A framework is a row in `technologies.tsv` (`ecosystem  pattern  name  category`, tab separated), not code. Languages (with file counts and whether they are analysed) and technologies are stored in the `analysis_metadata` table.

## Analysis pipeline (implemented)

`AnalysisPipeline` (`analysis/`) runs on the bounded `analysisExecutor` and moves the analysis through `CLONING` (5%) → `PARSING` (25%) → `ANALYZING` (60%) → `BUILDING_GRAPH` (80%) → `COMPLETED` (100%), each step a short write to PostgreSQL. Stages: `IngestionService` (clone + file discovery) → `ParsingService` (one `LanguageExtractor` per file) → `ReferenceResolver` (whole-repository second pass, IR → `CodeGraphIR`) → `GraphBuilder` (batched Cypher). The graph semantics (ids, relationship meanings, external nodes) are in `Architecture.md` → Notes.

- Anything thrown ends the analysis as `FAILED` with a reason. Throw `AnalysisFailureException` for expected failures: its message is stored and later shown to users, so it must contain no paths or internals. Any other error gets a generic reason; details go to the log.
- Never hold a database transaction while the pipeline runs, and keep `AnalysisService.createAnalysis` non-transactional: the row must be committed before the pipeline thread loads it.
- A partially written graph is deleted when `BUILDING_GRAPH` fails. `AnalysisRecovery` marks analyses left `QUEUED` or in progress by a restart as `FAILED` (single instance assumed).
- Parsing one bad file never fails the analysis; a repository with nothing parsable fails with a reason that lists the supported languages.

## Read endpoints (implemented)

Every read endpoint follows the same path, so a new one should reuse it:

- **Controller** (`codebase/` for `/repository`, `/files`, `/classes`; `graph/` for `/graph`, `/nodes`) takes the `analysisId` as a plain `String` path variable and calls a service.
- **`AnalysisService.requireCompleted(analysisId)`** is the single gate for serving results. It parses the id strictly as a canonical UUID (anything else cannot exist), then returns the UUID, or throws `AnalysisNotFoundException` (404) or `AnalysisNotCompletedException` (409, also for `FAILED`). `GraphService` calls it first, then hands `uuid.toString()` to the Neo4j queries, because that string is the `analysisId` stored on every node.
- **Neo4j reads** live in `graph/GraphQueryService` (counts, graph, node, files) and `graph/CodeQueryService` (file and class details). Cypher is built only from fixed strings and the label/relationship enums; ids, filters and paging are always bound parameters.
- **Postgres reads** go through `analysis/` services (`AnalysisService`, `AnalysisMetadataService`), never through its repositories from another feature.
- **Errors** are one exception per outcome in `common/`, mapped in `GlobalExceptionHandler`: `AnalysisNotFoundException` 404 `ANALYSIS_NOT_FOUND`, `NodeNotFoundException` 404 `NODE_NOT_FOUND`, `AnalysisNotCompletedException` 409 `ANALYSIS_NOT_COMPLETED`, `InvalidRequestException` and a bad enum or number in a query parameter 400 `VALIDATION_ERROR`, `ServiceBusyException` 503. Messages never echo user input.
- **Node ids** (`class:com.foo.A`, `file:src/A.java`, `method:com.foo.A#m(String)`) are what the API calls `nodeId`/`fileId`/`classId`. The prefix is the node type, so lookups pick the Neo4j label from it and use the `(analysisId, nodeId)` index; an unknown prefix is a 404 without a query. They contain `/`, `#`, `(` and spaces, so clients percent-encode the whole id and `common/TomcatConfig` sets Tomcat's `encodedSolidusHandling` to `passthrough`. Without it ids with a `/` never reach the controller.
- **External nodes** (`external: true`: library types, external modules) exist in the graph but are never returned as nodes or counted, except as labelled targets in a file's `imports`/`references` and a class's `superclasses`/`interfaces`.
- **Lists** paginate with a zero-based `page` and `size` (default 50, at most 200; out of range is a 400). Filters combine with AND.

## Testing

- Language tests run the real parser over fixture repositories under `src/test/resources/fixtures/polyglot/<language>/` through `FixtureAnalysis` (discovery, parsing and resolution without Spring); they need the native libraries. Tests that load the Spring context (`ArchyraApplicationTests`, `AnalysisPipelineIntegrationTest`, `PolyglotPipelineIntegrationTest`) use Testcontainers (`TestcontainersConfiguration` with `@ServiceConnection`, profile `test`) and need Docker, not local installs. The pipeline integration tests run the whole pipeline against real PostgreSQL, Neo4j and the real parser; only `GitCloner` is mocked, so it copies fixtures (`sample-repo`, or several `polyglot/*` directories assembled into one mixed repository). `RealRepositoryAnalysisTest` (opt-in, network) analyses a dozen real repositories, one per language.
- Test parsers and extractors as plain unit tests against small fixture source files under `src/test/resources/fixtures/`, without a Spring context.
- Use `@WebMvcTest` slices for controllers.
- Endpoint tests come in three layers: `@WebMvcTest` controller tests with a mocked service, Mockito unit tests of the services, and integration tests that run the real pipeline over `sample-repo`. The ones that need ids with slashes (`NodeEndpointHttpTest`, `FileAndClassEndpointHttpTest`) use `webEnvironment = RANDOM_PORT` and a real `HttpClient`, because MockMvc skips Tomcat and would not notice the `%2F` setting being removed.
- Mockito runs in strict-stubs mode with `@ExtendWith(MockitoExtension.class)`: a stub unused by a test fails it. A shared `setUp` stub that only some tests use needs `lenient()` (see `AnalysisServiceTest`).
- `./mvnw -q test` prints almost nothing on success; read the per-class counts in `target/surefire-reports/TEST-*.xml` (`tests`, `failures`, `errors`) when you need proof that a class actually ran.
