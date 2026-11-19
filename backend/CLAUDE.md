# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. It covers `backend/`.

System-wide architecture, database boundaries, API standards and the roadmap are in the root `CLAUDE.md` and `Architecture.md`. Setup, environment variables and provider configuration are in `README.md`. How the security works is in `spec/security/design.md`; the usage limits are specified in `spec/usage/spec-usage.md` and Ask AI in `spec/ask-ai-backend/spec.md`. There is no linter or formatter configured: `./mvnw clean test` is the only check.

## Gotchas

- **Use `./mvnw clean test` after changing a constructor or signature.** An incremental build keeps stale test classes and can hide compile errors.
- **`server.forward-headers-strategy` must stay `native`.** With `framework`, Spring 7 lets any client choose its own IP and dodge the rate limits.
- **Passwords are limited to 72 bytes** (bcrypt in Spring Security 7 rejects more). Do not raise `PasswordPolicy` limits without changing the encoder.
- **A class with two constructors needs `@Autowired` on the injection one**, otherwise the context fails with "No default constructor found" (this is the one exception to "no `@Autowired`").
- **Tests that start many analyses as one user need the high free-tier limit.** `application-test.properties` raises `archyra.usage.free-tier.*` to 100000; only the `Usage*IntegrationTest` classes bind the real small limits. Starting the same repository URL again while it still runs returns the running analysis, so a test that wants two analyses must use two URLs.
- **`redirect_uri_mismatch` is a provider-console problem, not a code one.** With `forward-headers-strategy=native` and the dev proxy's `Host`/`X-Forwarded-*`, the redirect URI is `http://localhost:5173/login/oauth2/code/{google,github}` (`OAuth2RedirectUriHttpTest` pins it over real HTTP; never hard-code a redirect URI). A GitHub OAuth App accepts one callback URL, Google several. See `README.md` → Provider set-up.
- **The development PostgreSQL must be a pgvector build** (e.g. `pgvector/pgvector:pg17`); a plain `postgres` image stops startup with a message. The application runs `CREATE EXTENSION IF NOT EXISTS vector` before Hibernate's schema update (`ai/PgVectorSetup`, an `EntityManagerFactoryDependsOnPostProcessor`), because an `ApplicationRunner` would be too late.
- **The embedding model is not in the `spring-ai-transformers` jar** (its `model.onnx` is a 133-byte pointer). Run `./scripts/fetch-embedding-model.sh`, or let the first start download it into `models/` (git-ignored). `EmbeddingService` loads it lazily; `archyra.ai.embedding.verify-on-startup=true` (false in tests) loads it at startup.
- **A killed test container looks like a flaky test.** If whole classes fail with "expected COMPLETED but was FAILED" and `Unable to connect to localhost:<port>` in the log, check `docker events --filter event=oom` and the Docker VM's memory before touching code.
- **The Groq base URL ends in `/v1`** (`https://api.groq.com/openai/v1`): the OpenAI SDK in Spring AI 2.0 appends only `/chat/completions`. Spring AI's auto-configuration is not used on purpose (explicit `Google GenAI` and `OpenAI` models, no default `ChatModel`).
- **A test dependency fell out of the classpath once**: Spring AI's ONNX dependency (DJL) brings `commons-compress` 1.27.1 closer to the root than the 1.28 Testcontainers needs, which dropped `commons-lang3` and broke Testcontainers' client; it is declared in `pom.xml` for tests.
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
├── analysis/      # Analysis lifecycle: /api/v1/analyses (create, list, status, delete, reanalyze), ownership, AnalysisRegistrar (the only way to create or delete one), background AnalysisPipeline
├── ingestion/     # Clone repository, file discovery, language detection
├── parsing/       # TreeSitterExtractor base, ParsingService, ReferenceResolver (IR → code graph IR)
│   └── lang/      # one extractor (and, where imports differ, one ModuleResolver) per language
├── technology/    # Frameworks, libraries, tools: technologies.tsv catalog, manifest reader, detector
├── ir/            # Archyra Intermediate Representation: plain language-agnostic model
├── graph/         # IR → Neo4j builder, graph queries, /graph and /nodes endpoints, premium gate
├── codebase/      # Read endpoints: /repository, /files, /classes
├── user/          # Accounts, linked social identities, /users/me, admin API (/admin/users); UserService is the entry point
├── auth/          # /api/v1/auth, refresh-token rotation, email flows, password change (/users/me/password), token purge, admin bootstrap
│   └── oauth2/    # Google and GitHub login: second filter chain, account resolver, handlers; account linking (link intents, binding cookie, link-mode handler, /users/me/linked-identities)
├── mail/          # EmailService and the asynchronous SMTP implementation
├── ai/            # Ask AI (V1): /ai-index endpoints, pgvector setup, the listeners of the pipeline events, AiProperties
│   ├── index/     # chunk capture, local ONNX embeddings, code_chunks / ai_index, embedding job, recovery sweeper, ChunkSearch
│   ├── retrieval/ # hybrid retrieval: name lookup, vector search, graph expansion, ranking and token budgets
│   ├── llm/       # ChatProvider (Gemini, Groq), ProviderGateway (fallback, cool-down); the only way to a model
│   └── chat/      # POST /chat, conversations, quota reservation and refund, prompt, grounding, answer cache
├── usage/         # Lifetime usage counters (user_usage), free-tier limits from configuration, GET /api/v1/usage
├── security/      # Properties, JWT, API filter chain, rate limiting, entitlements, audit log
└── common/        # Exceptions and their handler, shared config (clock, async, scheduling, Tomcat), TestPathClassifier (is this path test code?), the pipeline events (AnalysisGraphBuilt, AnalysisCompleted, AnalysisFailed, AnalysisDeleting)
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

## Usage limits

Free users have a lifetime allowance of analyses (`archyra.usage.free-tier.*`, never a literal in code); `usage/` holds the counters, `analysis/` consumes them. Full semantics: `Architecture.md` → Usage.

- **All analysis creation must go through `AnalysisRegistrar`; never call the pipeline directly.** The registrar locks the user's `user_usage` row, checks for a running duplicate, checks the limit, increments and inserts the analysis in one transaction. `AnalysisService.createAnalysis` itself stays non-transactional.
- **A new way to start an analysis must use `registrar.registerOrReuse`.** `reanalyze` does: `AnalysisService.start` is the shared path, and a re-run passes the finished analysis it replaces, which `AnalysisCleanup.deleteReplaced` removes only after the new one is `COMPLETED` (it never throws and only touches a finished analysis of the same owner). Anything that ends an analysis as `FAILED` must use `registrar.failAndRefund` (or `refundQuota`), which is idempotent.
- `UsageService.lock`, `charge` and `release` are `MANDATORY`-transactional on purpose: a unit is charged or released in the caller's transaction, never on its own. `AI_QUERY` is consumed by Ask AI (see below); a new type needs an enum value and one limit in `UsageProperties`, whose exhaustive switch will not compile without it.
- **Deleting an analysis gives its credit back to a free user.** Every deletion goes through `AnalysisCleanup.delete` → `AnalysisRegistrar.deleteAndCredit` (refund guard plus row delete in one transaction); never delete an `Analysis` row any other way. Premium users, `FAILED` analyses (already refunded) and pre-usage rows return nothing.
- `Analysis` is `@DynamicUpdate`. Keep it so: a full-row save would overwrite `quota_refunded` with a stale value and allow a second refund.
- `GET /usage` returns `UsageResponse { plan, usage: [UsageEntry { usageType, used, limit, remaining, limitReached }] }`; `limit` and `remaining` are `null` for PREMIUM.
- Usage integration tests extend `UsageIntegrationSupport` (stubbed pipeline) and use latches and barriers, never sleeps. Tests that create several analyses for one user must use a fresh user per test, because a free user stops at the limit.

## Analysis pipeline

`AnalysisPipeline` runs on `analysisExecutor`: `CLONING` (5%) → `PARSING` (25%) → `ANALYZING` (60%) → `BUILDING_GRAPH` (80%) → `COMPLETED` (100%), each step a short write to PostgreSQL. Stages: `IngestionService` → `ParsingService` (one `LanguageExtractor` per file) → `ReferenceResolver` (whole-repository second pass, IR → `CodeGraphIR`) → `GraphBuilder` (batched Cypher). Graph semantics (ids, relationships, external nodes) are in `Architecture.md` → Notes.

- Anything thrown ends the analysis as `FAILED`, together with returning its usage unit (one transaction). Throw `AnalysisFailureException` for expected failures: its message is stored and shown to users, so no paths or internals. Other errors get a generic reason; details go to the log.
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
- **Every endpoint that returns graph or file nodes must populate `test` through `common/TestPathClassifier`** (never a second copy of the rules). It is computed at read time from the stored `file` path, so it works on old analyses; `Method`/`Function`/`Class`/`Interface`/`File` nodes carry `file`, a `Module` is a test only if it has files and all are tests (read with the node), the `Repository` never is. `GET /files?test=true|false` needs the flag before paging, so with `test` the matching rows (id, name, path, language) are classified in Java and then paged; without it the paging stays in Cypher. Changing the rule table means changing `TestPathClassifierTest` and the table in `Architecture.md`.

## Neo4j rules

- Graph code lives in `graph/` and uses `Neo4jClient` and Cypher only. Spring Data Neo4j repositories are off (`spring.data.neo4j.repositories.type=none`) so they do not scan the JPA repositories.
- Every node carries `analysisId` and every query filters on it. Labels and relationship types match the root `CLAUDE.md` exactly; add new ones to `Architecture.md` first.
- Node ids are stable, derived from the analysis plus a qualified name. Never expose Neo4j internal ids.
- Write in batches (`UNWIND $rows ... MERGE`), not node by node. Map results into the React Flow `{ nodes, edges }` DTO in `graph/`; keep the mapper parameter-driven for V2 (`rootNodeId`, `depth`, `maxNodes`).
- PostgreSQL holds only metadata, accounts and tokens; code entities never go into it.

## Ask AI (`ai/`)

Repository-aware chat. Full design: `spec/ask-ai-backend/spec.md`; decisions and API: `Architecture.md` → Ask AI.

- **The pipeline never waits for AI, and AI never fails an analysis.** `analysis/` publishes events defined in `common/` (`AnalysisGraphBuilt` with the clone root, `AnalysisCompleted`, `AnalysisFailed`, `AnalysisDeleting`); `ai/` listens in `ai/AiAnalysisEvents`, `analysis/` never imports `ai/`. `AnalysisGraphBuilt` is handled **synchronously while the clone still exists** (chunk text is captured then, because the clone is deleted in `finally`) and its listener must never throw (any error becomes a FAILED index); the pipeline also guards it. Embedding runs after `COMPLETED` on `aiIndexExecutor` (one worker). `AnalysisDeleting` is published before the row is deleted and **may** throw: the delete then stops and the row stays.
- **Index lifecycle** (`ai_index`, `code_chunks`): no row is `NOT_INDEXED` (analysed before the feature: re-analyze, no backfill); `INDEXING` → `READY`; `FAILED` is retryable for free only when chunks exist; `EMPTY` means only test code. Status changes are guarded UPDATEs (`WHERE status = ...`) so a late job cannot resurrect or overwrite a row. `IndexRecovery` drops the data of analyses that did not complete and resumes `INDEXING` rows (startup and a sweep); a job continues where it stopped (only chunks without a vector). Test code (`TestPathClassifier`) is never indexed, retrieved or cited.
- **Chunks and vectors are plain SQL** (`ChunkStore`, `ChunkSearch`), never loaded as entities; `CodeChunk` exists to define the table. Spring AI is used for `EmbeddingModel` and chat only, not `PgVectorStore`. No vector index in V1 (exact cosine scan of one analysis). The vector size is `EmbeddingSpec.DIMENSION` (384) and also the column's: startup fails if the model differs.
- **Retrieval** (`ai/retrieval`) is deterministic and has a fixed priority: name matches, graph facts, vector hits, neighbours, sticky (nodes the previous answer cited). Graph reads are `graph/RetrievalGraphService` only (fixed Cypher, bound parameters, `analysisId` on every query, external and test nodes dropped). Only the best `vector-seeds-expanded` vector hits are expanded into facts, because facts rank above chunks and would otherwise fill a small budget.
- **Never call a provider directly: only through `ProviderGateway`.** It tries Gemini then Groq within `timeout.total`, never the same provider twice for one question, and caps one attempt at connect + read itself (the Gemini SDK's own timeout did not cut a slow call). No retries in the SDKs or Spring AI (`RetryPolicy.withMaxRetries(0)`, `maxRetries(0)`, one attempt). A 429 starts a cool-down (`Retry-After`, capped); a cooling or key-less provider is skipped and the pre-flight check refuses before anything is charged. The prompt is built **per provider** with that provider's context budget. Model ids, limits, base URLs and keys are configuration only, never Java literals.
- **Grounding** (`chat/Grounding`, `ChatService`): the model sees numbered items and never a node id. `[n]` markers outside code are read, numbers that are not items are dropped, the rest renumbered by first appearance, and **every source is re-verified in Neo4j** (a marker whose nodes vanished is dropped too). Repository text is untrusted data: it sits inside `<repository_context>` tags and a closing tag inside a chunk is defused.
- **Quota** (`chat/ChatTransactions`): reserve one `AI_QUERY` **before** the model call (`UsageService.lock` + `charge` in one transaction with the PENDING user message), no transaction while retrieval or the model run, then answer (`complete`) or **refund exactly once** (`UPDATE chat_messages SET quota_refunded = true WHERE id = ? AND quota_charged AND NOT quota_refunded AND status = PENDING`; only the winner calls `UsageService.release`). The `PENDING` condition is what stops a late failure from refunding an answered question. A cache hit stores both messages and is free. `ChatRecovery` refunds PENDING messages at startup; `ConversationService.purge` (analysis deleted or failed) refunds them before deleting. `ChatMessage` is `@DynamicUpdate`. Order of `ChatService.ask`: validate (no side effects) → cache → reserve → retrieve/prompt/call/ground → persist; anything after the reservation refunds.
- **Never log question text, code, answers, provider response bodies or keys.** `ChatService` logs only user id, analysis id, provider, latency, outcome and cached. A `ProviderException`'s cause can carry a provider's error body: never log or return it, and never put it in a message. Error bodies are fixed text.
- **The ONNX session is native memory: never close it under a running inference.** `EmbeddingService` takes a read lock around every batch and `destroy()` a write lock, and `EmbeddingJobs` stops quietly on `EmbeddingClosedException` (the index stays INDEXING and resumes at the next start). Closing a Spring context while an embedding job ran used to crash the whole JVM (SIGSEGV in `libonnxruntime`).
- **One question at a time per conversation** (`ChatInFlight`, in memory): `CONVERSATION_BUSY`; deleting a busy conversation is refused too. PENDING messages are never listed and never part of the history. History goes back without sources, within `history.turns` and `history.max-tokens`.
- **Tests**: `AiIntegrationSupport` (real pipeline, parser, PostgreSQL with pgvector, Neo4j, the real ONNX model; only `GitCloner` is replaced) and `ChatIntegrationSupport` (plus `FakeProviders`: WireMock as the fake Gemini and Groq, speaking the real wire formats, answers computed from the received prompt). Faults are injected with real database constraints (`ALTER TABLE ... ADD CONSTRAINT ... NOT VALID`), not mocks; races use latches and barriers. These classes close their Spring context after the class (`@DirtiesContext`), `src/test/resources/spring.properties` bounds Spring's context cache (`maxSize=6`) and `TestcontainersConfiguration` caps Neo4j's heap and page cache: every context holds a PostgreSQL and a Neo4j container, and dozens alive at once exhausted an 8 GB Docker VM, so the kernel OOM-killed Neo4j containers (Docker reports `oom`, exit 137) and the next test that reused that cached context failed with "connection refused" (it also killed a developer's own Neo4j container once). The first run downloads the embedding model (about 90 MB, into `models/`). `AiLiveSmokeTest` (opt-in, `ARCHYRA_AI_LIVE_TESTS=true`) asks one real question.

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

Summary; the full design is in `spec/security/design.md`, requirements in `spec/security/spec.md`.

- Two filter chains: the stateless API chain (`security/SecurityConfig`, JWT resource server) and a handshake-only chain for `/oauth2/**` (`auth/oauth2`). Everything under `/api/v1` needs a token except the public `/auth/**` routes. Admin routes need ROLE_ADMIN and are re-checked against the database.
- Access tokens (15 min, HS256) come only from `JwtService`; `sub` is the user id and the plan is never in the token. Refresh tokens are opaque, hashed, rotated, and sent in an `HttpOnly; SameSite=Strict` cookie; refresh and logout are the only CSRF-protected routes.
- **Account linking (Settings → Sign-in methods) is a separate mode of the OAuth handshake, not a login.** `LinkAuthorizationFilter` (before Spring's redirect filter) turns a valid `link_intent` **plus the matching `link_binding` cookie** into a link context in the handshake session; **never relax the cookie check** (without it a crafted URL attaches a victim's provider identity to an attacker's account). In link mode the user-info services return a `LinkPrincipal` and **must not call `SocialAccountResolver`** (it would create, email-match or take over accounts); `OAuth2LinkHandler` takes the user from the session, issues no tokens or cookies and ends the session. Link/unlink logic and its emails (sent after commit, never failing the operation) are in `user/IdentityLinkService`, which locks the user row. `hasPassword` is `passwordHash != null`. Details: `Architecture.md` → Linked sign-in methods, `spec/security/design.md` §6.
- **Changing the password from Settings** (`POST /users/me/password`, `auth/PasswordController` + `PasswordChangeService`) must keep three properties: the **current password is always verified** (a bearer token alone must not be enough), a wrong one **counts towards the same lockout as a failed login** (that, not a rate limit, bounds guessing), and success **revokes every refresh family and starts a new one for the caller in one transaction**. The wrong-password failure is recorded *outside* that transaction (`recordFailedLogin` has its own), so do not wrap the whole method in `@Transactional`: the throw would roll the failure count back. An account without a password gets `409 NO_PASSWORD_SET` and uses Forgot password: do not let a bearer token set a first password. Details: `Architecture.md` → Change password.
- **Register never reveals whether an address is registered**: same `202` and body for a new and an existing address, no `409`. An existing address is handled afterwards by `auth/ExistingAccountFlows` on the mail executor (verified: an "account already exists" notice via `EmailService.sendAccountExistsNotice`, at most one per address per hour through `RegisterNoticeThrottle`, links without a token; unverified: a fresh verification link and the stored name and password are *not* replaced; disabled: nothing), with a `REGISTER_EXISTING_ACCOUNT` audit line. Do not add a `409`; the reasoning is in `spec/security/design.md` §4.
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
