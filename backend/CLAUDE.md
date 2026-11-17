# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. It covers `backend/`.

System-wide architecture, database boundaries, API standards and the roadmap are in the root `CLAUDE.md` and `Architecture.md`. Setup, environment variables and provider configuration are in `README.md`. How the security works is in `specs/security/design.md`.

## Gotchas

- **Use `./mvnw clean test` after changing a constructor or signature.** An incremental build keeps stale test classes and can hide compile errors.
- **`server.forward-headers-strategy` must stay `native`.** With `framework`, Spring 7 lets any client choose its own IP and dodge the rate limits.
- **Passwords are limited to 72 bytes** (bcrypt in Spring Security 7 rejects more). Do not raise `PasswordPolicy` limits without changing the encoder.
- **A class with two constructors needs `@Autowired` on the injection one**, otherwise the context fails with "No default constructor found" (this is the one exception to "no `@Autowired`").
- **"Could not connect to Ryuk"** in a test run is a Docker/Testcontainers hiccup. Rerun before debugging.
- The `.env` file is git-ignored and `.env.example` lists the variables. Run everything from `backend/`, since `.env` and `native/` resolve against the working directory.

## Stack and commands

- **Java 25**, Spring Boot 4.0.9, Maven wrapper (3.9.16). A JDK 25 must be active (`JAVA_HOME`).
- Main libraries: webmvc, validation, Spring Data JPA (PostgreSQL), Spring Data Neo4j (Cypher through `Neo4jClient` only), Spring Security (resource server and OAuth2 client), Spring Mail, `RestClient`, Caffeine and Bucket4j (rate limits), `io.github.tree-sitter:jtreesitter` 0.27.0, Lombok.
- Tests: JUnit 5, Mockito, MockMvc, `spring-security-test`, Testcontainers (PostgreSQL, Neo4j), GreenMail (SMTP), WireMock (fake Google and GitHub).
- Boot 4 differences: the web starter is `spring-boot-starter-webmvc`, test starters are per technology (`*-test`), Jackson 3 is the default (`tools.jackson.*`, annotations still in `com.fasterxml.jackson.annotation`). Check Boot 4 docs, not Boot 3 habits.
- Config is `src/main/resources/application.properties` (not YAML). A new annotation processor must be added to both `annotationProcessorPaths` blocks in `pom.xml`.

```bash
./mvnw spring-boot:run                                   # run the app (port 8080)
./mvnw clean test                                        # all tests (Docker required)
./mvnw test -Dtest=AuthFlowIntegrationTest               # one test class
./mvnw test -Dtest=AuthFlowIntegrationTest#loginReturnsAnAccessTokenAndASecureRefreshCookie   # one method
./mvnw clean package -DskipTests                         # build the jar
./scripts/build-native.sh                                # build Tree-sitter runtime and grammars into native/
GRAMMARS="python go" ./scripts/build-native.sh           # only some grammars
ARCHYRA_NETWORK_TESTS=true ./mvnw test -Dtest='GitClonerTest,RealRepositoryAnalysisTest'   # opt-in tests that use GitHub
```

- **Native libraries:** run `./scripts/build-native.sh` once (git, make, a C compiler, and a C++ compiler for Ruby). Output goes to `native/` (`archyra.parsing.native-library-dir`). Parser tests and pipeline integration tests need it.
- **Services:** tests start their own PostgreSQL and Neo4j through Testcontainers. `spring-boot:run` needs real ones. Required variables: `DB_*`, `NEO4J_PASSWORD`, `JWT_SECRET`, `GOOGLE_*`, `GITHUB_*` (see `README.md`).
- `./mvnw -q test` prints almost nothing on success; read `target/surefire-reports/TEST-*.xml` (`tests`, `failures`, `errors`) for proof that a class ran.

## Package conventions

The base package is `com.abhiraj.archyra`, organised by feature (package-by-feature), not by layer.

- Only feature packages and `common/` sit directly under the base package. Never create root-level `controller/`, `service/`, `repository/`, `dto/` or `entity/`.
- Keep a feature's classes flat; add a subpackage only when it grows large (as `parsing/lang/` and `auth/oauth2/` did).
- A class lives in the feature that owns its use case. Shared code goes to `common/` only when two or more features need it, never to a generic `util/` or `helpers/`.
- Features talk through public services and IR/DTO types, never through another feature's repositories or internals. Repositories are package-private where possible.
- A new feature gets a new top-level package and the tree below is updated in the same change.

```
com.abhiraj.archyra
├── analysis/      # Analysis lifecycle: /api/v1/analyses (create, list, status, delete), ownership, background AnalysisPipeline
├── ingestion/     # Clone repository, file discovery, language detection
├── parsing/       # TreeSitterExtractor base, ParsingService, ReferenceResolver (IR → code graph IR)
│   └── lang/      # one extractor (and, where imports differ, one ModuleResolver) per language
├── technology/    # Frameworks, libraries, tools: technologies.tsv catalog, manifest reader, detector
├── ir/            # Archyra Intermediate Representation: plain language-agnostic model
├── graph/         # IR → Neo4j builder, graph queries, /graph and /nodes endpoints, premium gate
├── codebase/      # Read endpoints: /repository, /files, /classes
├── user/          # Accounts, linked social identities, /users/me, admin API (/admin/users); UserService is the entry point
├── auth/          # /api/v1/auth, refresh-token rotation, email flows, token purge, admin bootstrap
│   └── oauth2/    # Google and GitHub login: second filter chain, account resolver, handlers
├── mail/          # EmailService and the asynchronous SMTP implementation
├── security/      # Properties, JWT, API filter chain, rate limiting, entitlements, audit log
└── common/        # Exceptions and their handler, shared config (clock, async, scheduling, Tomcat)
```

- Controllers are thin: validate, call a service, map to a DTO. They never return JPA or Neo4j entities.
- Request and response DTOs are `record`s. Lombok is for boilerplate only (`@Getter`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`); no `@Data` on entities.
- Constructor injection only (`@RequiredArgsConstructor`, `final` fields); see the `@Autowired` exception under Gotchas.
- Enums from `Architecture.md` are Java enums named exactly like the API values: `AnalysisStatus`, `NodeType`, `RelationshipType`, later `ExpertiseLevel`.

## Endpoint and pipeline rules

- Routes live under `/api/v1`; per-endpoint behaviour is in `Architecture.md` next to each endpoint. `POST /analyses` returns `{ analysisId, status: "QUEUED" }` immediately; the pipeline runs `@Async` on a dedicated bounded executor (no Redis or RabbitMQ yet).
- Validation: Jakarta Validation on request records plus `@Valid`. `repositoryUrl` must be `https://github.com/{owner}/{repo}` (no other scheme or host, no `..`, no credentials). Bound every user-controlled query parameter.
- Ingestion: shallow clone (`--depth 1`) into a per-analysis temp directory, always deleted in `finally`. Enforce file-count and size limits; skip binaries, vendored code and build output.
- A single `@RestControllerAdvice` in `common/` maps one exception per outcome to `{ code, message }`. Messages never echo user input.

## Analysis pipeline

`AnalysisPipeline` runs on `analysisExecutor`: `CLONING` (5%) → `PARSING` (25%) → `ANALYZING` (60%) → `BUILDING_GRAPH` (80%) → `COMPLETED` (100%), each step a short write to PostgreSQL. Stages: `IngestionService` → `ParsingService` (one `LanguageExtractor` per file) → `ReferenceResolver` (whole-repository second pass, IR → `CodeGraphIR`) → `GraphBuilder` (batched Cypher). Graph semantics (ids, relationships, external nodes) are in `Architecture.md` → Notes.

- Anything thrown ends the analysis as `FAILED`. Throw `AnalysisFailureException` for expected failures: its message is stored and shown to users, so no paths or internals. Other errors get a generic reason; details go to the log.
- Never hold a database transaction while the pipeline runs, and keep `AnalysisService.createAnalysis` non-transactional: the row must be committed before the pipeline thread loads it.
- A half-written graph is deleted when `BUILDING_GRAPH` fails. `AnalysisRecovery` marks analyses left in progress by a restart as `FAILED` (single instance assumed).
- One unparsable file never fails the analysis; a repository with nothing parsable fails with a reason that lists the supported languages.

## Read endpoints

Every read endpoint follows one path, so a new one should reuse it:

- The controller takes `analysisId` as a plain `String` and calls a service.
- **`AnalysisService.requireCompleted(analysisId)` is the single gate.** It enforces **ownership** (the analysis belongs to the caller, else 404, admins included), parses the id strictly as a canonical UUID, and throws `AnalysisNotFoundException` (404) or `AnalysisNotCompletedException` (409, also for `FAILED`). `GraphService` calls it first and passes `uuid.toString()` to Neo4j, because that string is the `analysisId` stored on every node.
- Neo4j reads live in `graph/GraphQueryService` and `graph/CodeQueryService`. Cypher is built only from fixed strings and the label/relationship enums; ids, filters and paging are always bound parameters. Postgres reads go through `analysis/` services.
- **Node ids** (`class:com.foo.A`, `file:src/A.java`, `method:com.foo.A#m(String)`) are the API's `nodeId`/`fileId`/`classId`. The prefix is the node type; an unknown prefix is a 404 without a query. They contain `/`, `#`, `(` and spaces, so clients percent-encode them, `common/TomcatConfig` passes `%2F` through and `SecurityConfig.httpFirewall()` allows it. Without both, ids with a `/` never reach the controller.
- **External nodes** (`external: true`: library types, external modules) exist in the graph but are never returned or counted, except as labelled targets in `imports`/`references` and in a class's `superclasses`/`interfaces`.
- Lists paginate with zero-based `page` and `size` (default 50, at most 200; out of range is a 400). Filters combine with AND.

## Neo4j rules

- Graph code lives in `graph/` and uses `Neo4jClient` and Cypher only. Spring Data Neo4j repositories are off (`spring.data.neo4j.repositories.type=none`) so they do not scan the JPA repositories.
- Every node carries `analysisId` and every query filters on it. Labels and relationship types match the root `CLAUDE.md` exactly; add new ones to `Architecture.md` first.
- Node ids are stable, derived from the analysis plus a qualified name. Never expose Neo4j internal ids.
- Write in batches (`UNWIND $rows ... MERGE`), not node by node. Map results into the React Flow `{ nodes, edges }` DTO in `graph/`; keep the mapper parameter-driven for V2 (`rootNodeId`, `depth`, `maxNodes`).
- PostgreSQL holds only metadata, accounts and tokens; code entities never go into it.

## Tree-sitter rules

- Tree-sitter types stay inside `parsing/`. Extractors turn ASTs into IR; everything downstream depends only on the IR.
- One extractor per language behind `LanguageExtractor`, by extending `TreeSitterExtractor`. Adding a language must not touch the graph builder or the resolver. Extractors walk the tree through the base-class hooks, not `.scm` queries; print a real parse to check node and field names instead of guessing.
- Binding is the official `jtreesitter` (FFM API). Do not switch to JNI bindings (reasoning in `Architecture.md` → Technologies → Notes). Upgrading it may raise the minimum Java version, so check its `maven.compiler.release` first.
- It ships no native code: the runtime and grammars are built by `scripts/build-native.sh` and loaded only by `parsing/NativeLibraries`. Any launcher needs `--enable-native-access=ALL-UNNAMED` (already set for Surefire and `spring-boot:run`; a jar or Docker entrypoint needs it too). Packaging the libraries for deployment is still to do.
- `ReferenceResolver` resolves calls, type usages and imports after every file is parsed. Targets outside the repository become `external` nodes; a reference tied to nothing known is counted and skipped, never guessed.
- Parsing one bad file must not fail the analysis: log it, record it, continue.

### Languages and technologies

- **Analysed** (classes, functions, imports, calls): Java, Kotlin, Python, JS/JSX, TS/TSX, Go, Rust, C#, PHP, Ruby, Solidity, Vue and Svelte (`<script>`), Jupyter notebooks. **Counted only**: Swift, Dart, C, C++, Scala, R, Julia, shell, SQL, HCL, Dockerfile, HTML, CSS.
- Extractors implement only the hooks their language has; never force one language's concepts into another (free functions are `Function` nodes, not fake classes).
- **IR contract:** a file has a `moduleName` (what qualifies its symbols and what imports name) and a `packageName` (the Module node grouping). A type is `FULL`, `PARTIAL` (C# `partial`, Ruby open classes) or `EXTENSION` (Go receivers, Rust `impl`). `ModuleResolver`s map import specifiers to modules; `ReferenceResolver` itself has no language rules.
- **To add a language:** (1) a row in `GRAMMARS_TABLE` of `scripts/build-native.sh`; (2) the `Language` value and extensions in `ingestion/LanguageDetector`; (3) an extractor `@Component` in `parsing/lang/`; (4) a `ModuleResolver` only if imports differ from "the import names the module"; (5) a fixture under `src/test/resources/fixtures/polyglot/<language>/`, a test on `FixtureAnalysis`, and the new classes in the test helper `Languages`; (6) rows in `src/main/resources/technologies.tsv`.
- **Technologies** are detected best-effort (and can never fail an analysis) from files, manifests, framework languages and imports. A framework is a tab-separated row in `technologies.tsv`, not code. They are stored in `analysis_metadata`.

## Security

Summary; the full design is in `specs/security/design.md`, requirements in `specs/security/spec.md`.

- Two filter chains: the stateless API chain (`security/SecurityConfig`, JWT resource server) and a handshake-only chain for `/oauth2/**` (`auth/oauth2`). Everything under `/api/v1` needs a token except the public `/auth/**` routes. Admin routes need ROLE_ADMIN and are re-checked against the database.
- Access tokens (15 min, HS256) come only from `JwtService`; `sub` is the user id and the plan is never in the token. Refresh tokens are opaque, hashed, rotated, and sent in an `HttpOnly; SameSite=Strict` cookie; refresh and logout are the only CSRF-protected routes.
- **Ownership is enforced in `AnalysisService` only**; a new endpoint must go through `requireCompleted`. Foreign and unknown ids both give 404.
- **Premium is read from the database** by `EntitlementService` (never the token). `graph/GraphPremiumGate` lets FREE use no parameters, `nodeType`, `relationshipType` and `level=BEGINNER`; everything else is `403 PREMIUM_FEATURE`. New V2/V3 endpoints call `requirePremium(Feature)` themselves.
- Rate limits, the audit log (`security.audit`, never a secret), token purge and admin bootstrap are described in the design document.
- **Tests:** slices import `security/SecuritySliceConfig` and use `@WithMockUser`; real-HTTP tests mint tokens with `JwtService`; `EveryEndpointIsProtectedTest` fails if a new endpoint is left open. A new public endpoint must be added to its explicit list.

## Testing

- Language tests run the real parser over `src/test/resources/fixtures/polyglot/<language>/` through `FixtureAnalysis` (no Spring); they need the native libraries.
- Tests that load the Spring context use Testcontainers (`TestcontainersConfiguration`, `@ServiceConnection`, profile `test`) and need Docker. Pipeline integration tests run the real pipeline against real PostgreSQL, Neo4j and parser; only `GitCloner` is mocked (it copies fixtures such as `sample-repo`).
- Endpoint tests come in three layers: `@WebMvcTest` controller tests with a mocked service, Mockito unit tests of services, and integration tests. Tests that need ids containing `/` (`NodeEndpointHttpTest`, `FileAndClassEndpointHttpTest`) use `RANDOM_PORT` and a real `HttpClient`, because MockMvc skips Tomcat and would not notice the `%2F` setting being removed.
- Mockito runs in strict-stubs mode: an unused stub fails the test. A shared `setUp` stub that only some tests use needs `lenient()`.
- Do not name a test helper `get`, `post` or `delete` in a class that statically imports MockMvc's: it silently shadows them (use `getAs`, `postJson`).
