# Archyra
> *The architecture behind your code*
>

# Versions

## V0.1 | Codebase Understanding

- Goal: Give Archyra a repository and get a structured representation of it.

---

- [x]  Accept a GitHub repository
- [x]  Analyze the repository
- [x]  Detect files, classes, methods, imports and references
- [x]  Identify relationships between classes/files
- [x]  Build the internal code graph
- [x]  Portray the relationships visually
- [ ]  Show basic technology/framework information (detected and stored in `analysis_metadata`, not exposed by an endpoint yet)

---

- The important thing here is that **Archyra is already doing something genuinely useful without AI**.
- For ex:

    ```tsx
    UserController
          │
          ▼
     UserService
          │
          ▼
     UserRepository
          │
          ▼
       Database
    ```


## V0.5 | Product Foundation

- Goal: Turning the engineering prototype into a service

---

- [x]  Google authentication (plus GitHub and email/password)
- [x]  User accounts
- [x]  Repository history
- [x]  Usage tracking
- [x]  Free usage limits
- [x]  Admin dashboard
- [x]  Total users
- [x]  Active users
- [x]  Premium users
- [x]  Analysis statistics
- [x]  Basic account/settings page

---

- Users should be able to store some data regarding the repositories they have analysed for future references.

## V1 | AI Codebase Explorer

- Goal: Make the AI identity of Archyra visible

---

- [ ]  **Ask AI** (backend built, see "Ask AI" below; the UI is next)
- [ ]  Repository-aware questions, Relationship explanations, Architecture explanations. For example:
    - "How does authentication work?"
    - "Where does a request to `/users` go?"
    - "What classes depend on `PaymentService`?"
    - "Explain this class"
    - "What is the role of this module?"
- [ ]  RAG over the repository/code graph
- [ ]  Refined, modular frontend

---

- The graph tells AI what exists and how things are connected. AI explains what those relationships mean.

    ```tsx
    Repository
         ↓
    Static Analysis
         ↓
    Code Graph
         ↓
     ┌───────────────┐
     │               │
    Graph         Code Context
     │               │
     └───────┬───────┘
             ↓
            AI
             ↓
       Explanation
    ```


---

- [ ]  Deploy Archyra
- [ ]  Put it in resume
- [ ]  Make a linkedin post about it.

## V2 | Interactive Architecture Graph

- Goal: Make the graph explorable.

---

- What it mean?
    - At the first level:

    ```
              UserController
                    │
                    ▼
               UserService
              ↙          ↘
     UserRepository    EmailService
           │                │
           ▼                ▼
       Database          Mail API
    ```

  Then the user clicks:

  **`UserService`**

  Archyra zooms into that class and generates a new graph based on its methods.

  For example:

    ```
                     UserService
                         │
            ┌────────────┼────────────┐
            ▼            ▼            ▼
         getUser()   createUser()  deleteUser()
            │            │            │
            ▼            ▼            ▼
     UserRepository  UserRepository UserRepository
                         │
                         ▼
                      UserMapper
    ```

  And clicking `createUser()` could take you another level deeper:

    ```
    createUser()
         │
         ├── validateUser()
         │
         ├── UserRepository.save()
         │
         ├── PasswordEncoder.encode()
         │
         └── EmailService.send()
    ```

- It should only be available for the premium users.

---

- [ ]  Click node → expand
- [ ]  Double-click → enter node
- [ ]  Hover → relationship information
- [ ]  Search class/method
- [ ]  Filter relationship types
- [ ]  Hide unrelated nodes
- [ ]  Backtrack through exploration
- [ ]  Zoom in/out
- [ ]  Highlight dependency paths
- [ ]  Colour should communicate semantics:
    - class
    - interface
    - external dependency
    - database
    - API
    - method
    - inheritance
    - dependency
    - method call

## V3 | Adaptive Architecture

- Goal: Different graphs for different expertise level. (Beginner, Intermediate, Advanced)
- The free tier should only have the beginner graphs.

---

- Make the difference between free and premium features:


    | Feature | Free | Premium |
    | --- | --- | --- |
    | Repository analysis | ✅ | ✅ |
    | Basic architecture graph | ✅ | ✅ |
    | Actual classes/modules | ✅ | ✅ |
    | Basic relationships | ✅ | ✅ |
    | Ask AI | Limited | More |
    | Interactive graph | ❌ | ✅ |
    | Class → method exploration | ❌ | ✅ |
    | Method-level dependencies | ❌ | ✅ |
    | Deep relationship exploration | ❌ | ✅ |
    | Beginner graph | ✅ | ✅ |
    | Intermediate graph | ❌/limited | ✅ |
    | Expert graph | ❌ | ✅ |
    | Advanced graph filters | ❌ | ✅ |


---

# Technologies

- Backend
    - Java 25 (LTS)
    - Spring Boot
    - Spring Security
    - JWT
    - Maven
- Database
    - PostgreSQL
    - Spring Data JPA
    - Hibernate
- Graph database
    - Neo4j (the code graph; Cypher through Spring Data Neo4j's `Neo4jClient`)
- Frontend
    - React
    - Typescript
    - Vite
    - Tailwind
    - React Router
    - TanStack Query
    - Playwright (end-to-end tests and the screenshot capture script)
- Graph Visualization
    - React Flow (`@xyflow/react`; the backend gives JSON like `{edges: [], nodes: []}`)
    - dagre (`@dagrejs/dagre`) for the layered, top-to-bottom layout, run in a Web Worker
    - Custom Graph UI from V2
- Code Analysis Engine
    - Tree-sitter, via the official Java binding `io.github.tree-sitter:jtreesitter` (0.27.0)
- Vector Databse
    - PostgreSQL + pgvector
- AI
    - Spring AI
- LLM
    - V1 Ask AI uses a configurable chain of free-tier providers, Gemini first and Groq, OpenRouter, Cloudflare Workers AI, Z AI, Cohere, OVHcloud and LLM7 behind it (see "Decision: Ask AI provider chain"); GPT and Claude were not compared (see "Decision: Ask AI backend")
- Github Integration
    - Github API
    - Git
- Payment
    - Razorpay
- Background Processing (Needed after there are multiple users using the product and server is taking a lot of time to give output)
    - Reddis
    - RabbitMQ

## Notes

### Decision: Tree-sitter binding and Java version (2026-11-14)

- **Chosen:** the official `io.github.tree-sitter:jtreesitter` binding (0.27.0), with the project on **Java 25 (LTS)**.
- **Why Java 25:** jtreesitter uses Java's Foreign Function & Memory API. Versions 0.26.0 and later are compiled for Java 23, and 0.25.6 is the last release that runs on Java 22. Java 22 and 23 are non-LTS releases that no longer get updates, so the project moved to Java 25, the current LTS, which can use the latest binding.
- **Rejected:** the JNI-based `tree-sitter-ng` binding (it would keep Java 21 but isn't the official binding), and Java 22 with jtreesitter 0.25.6 (an outdated binding on an unsupported Java release).
- **Consequences:**
    - jtreesitter does not bundle native code. The tree-sitter runtime library and each language grammar must be available as native shared libraries at runtime, and be packaged for deployment.
    - The JVM needs `--enable-native-access=ALL-UNNAMED` to avoid native-access warnings, and to keep working once those warnings become errors.

### Decision: Code graph semantics for V0.1 (2026-11-15)

- **Node ids** (`nodeId`, scoped by `analysisId`): `repository:<name>`, `module:<package>`, `file:<path>`, `class:<qualified name>`, `interface:<qualified name>`, `method:<qualified name>#<name>(<parameter types>)`, `function:<path>#<name>(<parameter types>)`. External nodes are prefixed `external:`, for example `external:class:java.util.List`. Neo4j internal ids are never exposed.
- **Modules** are Java packages (`(default)` for the default package). Containment is `Repository → Module → File → Class/Interface/Function → Method`, with nested types contained in their enclosing type. Enums and records are `Class` nodes and annotation types are `Interface` nodes; the node property `kind` keeps the original declaration kind.
- **Relationships:**
    - `EXTENDS` and `IMPLEMENTS` between types.
    - `IMPORTS` from a file to the file that declares the imported type (external imports point to an external node).
    - `REFERENCES` from a class (field types) or method (signature, locals, `new`, including generic type arguments) to the types it mentions.
    - `CALLS` between methods, resolved from the declared type of the receiver (field, parameter, local, `this`, `super`, unqualified or static call), including inherited methods.
    - `DEPENDS_ON` between classes and interfaces **of the repository only**, derived from `REFERENCES` and `CALLS`. It is the relationship that draws the architecture (controller → service → repository); external types are never `DEPENDS_ON` targets.
- **External and unresolved targets:** a target outside the repository becomes a node with `external: true` (for example an imported `java.util.List`, or a call on it) instead of being dropped. Heritage that cannot be resolved at all (such as `extends Exception` without an import) also becomes an external node. A reference with no import and no declaration in the repository (a `java.lang` type, a generic type parameter) produces no node. Calls whose receiver type cannot be determined (chained expressions such as `a.b().c()`, accessors generated by Lombok or records) are counted and skipped. Resolution is by declared type, not a full type checker; on `spring-petclinic` roughly a third of calls stay unresolved. Graph endpoints should exclude `external` nodes unless asked for them.
- **Languages:** see "Multi-language analysis" below. An analysis with no parsable file fails with a clear reason.
- **Pipeline and failures:** status and progress live in PostgreSQL (`analyses`); the graph lives in Neo4j. Stages run in the background on a bounded executor, a full queue returns `503 SERVICE_BUSY`, and every error ends as `FAILED` with a user-safe reason.

### Decision: Multi-language analysis (2026-11-15)

- **Goal:** analyse mixed repositories (backend, frontend, mobile, data science, smart contracts, infrastructure), not only Java, without changing the pipeline per language.
- **Analysed languages:** Java, Kotlin, Python, JavaScript/JSX, TypeScript/TSX, Go, Rust, C#, PHP, Ruby, Solidity, Vue and Svelte single-file components (the `<script>` blocks, with original line numbers), Jupyter notebooks (code cells joined as Python). Swift, Dart, C, C++, Scala, R, Julia, shell, SQL, HCL, Dockerfile, HTML and CSS are detected and counted in the language statistics but contribute no code nodes. Adding one is an extractor plus a row in the native build script.
- **Design:** every language is an extractor on a shared `TreeSitterExtractor` base; the IR is language-neutral (`moduleName`, `ImportIR` as symbol/module/wildcard bindings, `Declaration` FULL/PARTIAL/EXTENSION, more `TypeKind`s) and name resolution is generic. Only the mapping from an import specifier to a module of the repository is per language (`ModuleResolver`).
- **Concepts are not forced:** languages without classes (Go, JS scripts, Python scripts) produce `Function` nodes contained in their `File`; Go receiver methods and Rust `impl` blocks merge into their struct; C# `partial` and Ruby reopened classes merge. Top-level script statements are not attributed to a synthetic function.
- **Imports:** `IMPORTS` links a file to the files it imports in any language, following re-exports (Python `__init__`, JS barrels). An unresolved import becomes an external **Module** node (`external:module:numpy`, `external:module:react`) in languages that import modules, and an external Class node in languages that import types (Java, Kotlin, C#, PHP). A call into an external module becomes an external `Function` node.
- **Module dependencies:** `DEPENDS_ON` also connects Module nodes (derived from imports across modules), so function-oriented languages still get an architecture view. Class `DEPENDS_ON` remains class to class within the repository.
- **Resolution limits (static, no type checker):** receivers are typed from declarations, annotations, constructor-parameter properties, `new`/constructor calls and the declared return type of the called function; dynamic languages (Python, JS, Ruby) resolve less than typed ones. Calls on unknown receivers are counted and skipped. Python `self.x` fields are typed from `__init__`; Ruby `@x` from `X.new`.
- **Technologies:** frameworks, libraries and tools are detected from manifests, files, framework languages and imports, using `technologies.tsv` (a data file, one row per library). Languages (with file counts and an `analysed` flag) and technologies (with category, ecosystem and evidence file) are stored in the PostgreSQL `analysis_metadata` table for the repository information endpoint. Detection is best effort and never fails an analysis.

### Decision: One session-boot path (2026-11-19)

- **Problem:** after a Google/GitHub login the app could stay on "Opening Archyra". The OAuth callback page ran a second session hydration (a second `POST /auth/refresh`, then `queryClient.clear()`) while the first boot's `/users/me` query was mounted; the clear aborted the in-flight request and left the observer on a removed query, so the guard waited forever. It is a race, seen in development and in a production build. Evidence: `spec/oauth-login-hydration-and-account-linking/root-cause.md`.
- **Chosen:** `hooks/session.tsx` is the only hydration path: csrf → refresh → `/users/me` (seeded into the cache) as a module-level single flight, so exactly one refresh happens per page load whatever StrictMode or remounts do. `/auth/callback` only waits for it: authenticated goes to the remembered destination, no session goes to `/login?error=oauth_failed`.
- **Never spin forever:** the whole boot has a 10 s timeout that aborts the in-flight auth requests and shows a retry screen (*Try again*, *Back to sign in*). A `403 CSRF_TOKEN_INVALID` on refresh or logout refetches the CSRF token and retries once (never on a 401).
- **Rule:** never `queryClient.clear()` and then rely on a refetch while `useQuery` observers are mounted; clear, then `setQueryData`.

### Decision: Account linking is a separate handshake mode (2026-11-19)

- **Chosen:** linking a provider to a signed-in account reuses the OAuth2 chain but is switched on only by a short-lived, single-use link intent that is bound to the asking browser with an HttpOnly cookie, never by the provider's email. In link mode the account resolver is not called.
- **Why the cookie:** without it, an attacker could send a victim a crafted authorization URL and attach the victim's provider identity to the attacker's account.
- **Rejected:** auto-linking by email in link mode (the person proved control of the provider by completing the login while signed in, so no email match is needed and a different email is allowed), account merging (an identity already linked elsewhere is refused without naming the owner), and a "set password" flow for OAuth-only users (they use Forgot password).
- **Consequences:** one identity per provider per user (unique `(user_id, provider)`); unlinking never removes the last way to sign in; both actions email the owner after commit. Endpoint behaviour is under User → Linked sign-in methods.

### Decision: Ask AI backend (2026-11-19)

Full design and the decision table: `backend/spec/ask-ai-backend/spec.md`. As built:

- **AI stack.** Spring AI 2.0.2 (Boot 4.0), used only for `EmbeddingModel` and chat, through the explicit modules `spring-ai-transformers`, `spring-ai-google-genai` and `spring-ai-openai` (no auto-configuration, so no default `ChatModel` exists for other code to pick up). Chat: a chain of free-tier providers (Gemini first; see "Decision: Ask AI provider chain"), all behind `ChatProvider`; the only way to a model is `ProviderGateway`. Model ids, base URLs, limits and keys are configuration (`archyra.ai.providers.*`), never Java. Embeddings run in the JVM (ONNX `all-MiniLM-L6-v2`, 384 dimensions): no key, no rate limit. Vectors live in our own `code_chunks` table (pgvector, cosine `<=>`, exact scan of one analysis, no vector index in V1), not in Spring AI's `PgVectorStore`.
- **Chunk text vs the deleted clone.** The pipeline deletes the clone in `finally`, and the IR had no source text, so chunk text is captured **while the clone exists**: the pipeline publishes `AnalysisGraphBuilt` (defined in `common/`, handled synchronously by `ai/`) after the graph is written and before `COMPLETED`. The IR, the graph and `GET /nodes/{nodeId}` gained `endLine`. One chunk per method or function (`line..endLine`) and one header chunk per type (up to its first member, at most 80 lines; a type without members gets its whole declaration), test code and external nodes excluded, capped at `chunk.max-per-analysis`, over-long text truncated with a marker. Capture can never fail an analysis: any error becomes a `FAILED` index with no chunks.
- **Index lifecycle.** `ai_index` per analysis (no row is `NOT_INDEXED`: analyses from before the feature are not indexed and are not backfilled, the user re-analyzes). Capture stores the chunks and an `INDEXING` row (`EMPTY` when only test code); after `COMPLETED` a one-worker executor embeds the chunks without a vector in batches, committing each; `READY` when all have one. A failed embedding is `FAILED` and retryable for free (`POST .../ai-index`); a failed capture is not (re-analyze). A startup step and a scheduled sweep drop the data of analyses that did not complete and resume `INDEXING` rows where they stopped; a full queue is the same case. The analysis never waits for any of this. Deleting or replacing an analysis removes its index, chunks, conversations and messages before the row goes.
- **Retrieval.** Hybrid and deterministic. Identifiers in the question are looked up in the graph (name, qualified name, file suffix), the question is embedded and the nearest chunks above `min-similarity` are taken, the nodes the previous answer cited are sticky seeds, and the named nodes, the best `vector-seeds-expanded` vector hits and the sticky nodes are expanded one hop through `DEPENDS_ON`, `CALLS`, `EXTENDS`, `IMPLEMENTS` (both directions, at most `neighbors-per-node`) into **facts** such as `UserController DEPENDS_ON UserService`. Ranked: name matches, facts, vector hits, neighbours, sticky; fitted to the **chosen provider's** token budget (Groq's is small because one request above its per-minute token limit can never succeed), so a fallback rebuilds its prompt. A named node whose relationships were cut gets a fact saying so. This is what answers "what depends on X?" even when no vector is close.
- **Grounding.** The model sees numbered context items and never a node id. The server reads the `[n]` markers (not inside code), drops numbers that are not items, renumbers by first appearance, maps items to nodes and **re-verifies every source node in Neo4j** (a marker whose nodes vanished is dropped too). No citation means `sources: []`. Repository text is untrusted data inside `<repository_context>` tags; a closing tag inside a chunk is defused.
- **Quota semantics.** One `AI_QUERY` is reserved before the model call, in one short transaction with the PENDING user message (`UsageService.lock` + `charge`, like `AnalysisRegistrar`); no transaction is open during retrieval or the model call. Any failure after that refunds exactly once (a guarded UPDATE on `chat_messages`, only for a still-PENDING message) and removes the PENDING message and a conversation created for it, so history never holds an unanswered question. A cache hit stores both messages and is free. A crash is repaired at startup; deleting an analysis first refunds its pending questions. FREE: 20 questions for life (`AI_LIMIT`), PREMIUM is counted and never blocked, everyone: 10 questions per minute (`RATE_LIMITED`). Deleting a conversation does not give questions back.
- **Provider fallback and cool-down** (the original two-provider version, generalised by the provider chain below). Gemini then Groq within `timeout.total`; the same provider is never tried twice for one question and there are no retries inside the SDKs. A 429, a 5xx, a timeout, a connection error, an empty or blocked answer and a bad key (logged loudly) each move on to the next provider. A 429 puts that provider in cool-down for its `Retry-After` (Groq sends one; the Gemini SDK does not expose headers, so Gemini uses the default), capped at `cooldown-max`; the pre-flight check skips cooling providers and refuses with `503 AI_UNAVAILABLE` **before** anything is charged. One attempt is capped at connect + read by the gateway itself, because the Gemini SDK's own timeout did not cut a slow call.
- **Privacy.** Questions and code excerpts go to third-party providers on their free tiers, some of which log them or use them to improve their products (see the provider chain below); Archyra only clones public repositories. Nothing sent or received (question, code, answer, provider body) and no key is ever logged.

### Decision: Ask AI provider chain (2026-11-19)

Full spec and the provider research: `spec/ask-ai-providers/spec.md`. Only the provider layer changed; retrieval, grounding, conversations, quota and refunds are as above.

- **Chosen:** a configurable chain of free providers, from <https://github.com/mnfst/awesome-free-llm-apis> (re-read on the day, every limit and URL checked against the provider's own docs where they were reachable). `archyra.ai.providers.order` lists the ids (default `gemini,groq,openrouter,cloudflare,zai,cohere,ovh,llm7`); each id has a block `archyra.ai.providers.<id>.*` (protocol, base URL, key, model, context budget, requests per minute / concurrent / per day / per month, `logs-prompts`). One generic OpenAI-compatible client serves every provider that speaks chat completions; Gemini keeps its dedicated, tested adapter. **A new provider is a config block plus its id in `order`**: no Java.
- **Tiers.** *Standard* (in the chain when its key is set): Gemini, Groq, OpenRouter, Cloudflare Workers AI (needs the account id in the base URL), Z AI, Cohere, OVHcloud (no key: anonymous mode, see below) and LLM7. *Opt-in* (off unless `enabled=true`, with a startup warning, appended after the listed providers): NVIDIA NIM (hosted endpoints are for prototyping and log submitted data), Kilo (its free router may use providers that log prompts) and Gonka (a phone-verified key). *Not implemented*: ModelScope and SiliconFlow (mainland-China real-name verification: cannot be tested) and Aion Labs (roleplay model). Why the split: a provider that states it logs or trains on prompts, or that needs a decision from the operator, must not receive repository text because a key happened to be in the environment.
- **Safety rules (enforced in code, tested).** OpenRouter refuses startup unless its model id ends in `:free`. OVHcloud is used anonymously only: no `Authorization` header is ever sent (keyed use needs a payment method on the project, so it is billed), and a configured key is ignored with a warning. Cohere logs a startup warning (trial keys are non-commercial). A key goes only to the provider whose block holds it, and two active providers with the same key refuse startup. Keys never appear in logs, errors, the admin endpoint or API responses; the Gemini SDK's retry template is kept from logging a provider's error body.
- **Local protection per provider** (so a free quota is not burnt on calls that would be refused): a Bucket4j bucket for requests per minute, a semaphore for calls in flight (Z AI allows one), UTC-day and UTC-month request counters (in memory: a restart resets them, which is accepted because the provider's own 429 then triggers the cool-down), and a context budget per provider (the prompt is built per provider; budgets are conservative readings of each provider's token or neuron limits). A local refusal skips to the next provider and does not count as an attempt.
- **Attempts and time.** At most `providers.max-attempts` (4) providers are called per question, each given an equal share of the time left (never less than `min-attempt-timeout`, never more than connect + read), all inside `timeout.total`.
- **Cool-down.** A 429 uses `Retry-After`, else `cooldown-default` (30 s); every further 429 in a row waits at least twice as long (never less than `Retry-After`) up to `cooldown-max` (1 h), and a success resets it, so a provider whose daily quota is spent is probed less and less. A bad key (401, 402, 403) or an unknown model (404) cools the provider down for `cooldown-misconfigured` (30 min) with an `ERROR` log; nothing crashes. The pre-flight check still refuses with `503 AI_UNAVAILABLE` **before** charging when no provider is usable (none configured, all cooling down, or all at a local limit). The refund-once rule is unchanged: a question is refunded only when every attempt failed.
- **Output.** `<think>` blocks are removed and reasoning fields never read (any provider, Gemini included), the text is trimmed, an empty or blocked completion is a provider failure, and an `error` object inside a 200 body is classified like its status. Citations are parsed and verified in Neo4j on the cleaned text, unchanged.
- **Identity.** `provider` is a free-form id (`groq`, `openrouter` ...; lower case) and `chat_messages.provider` is `varchar(40)` without an enum; `providerName` (the configured display name) is added to the chat response and to message items. `GET /api/v1/admin/ai/providers` shows the state of the chain to administrators.
- **Data-use stance.** Free tiers are paid for with data: some of these providers log prompts or use them for training or product improvement (Gemini's free tier, OpenRouter's free providers, NVIDIA, Kilo's router), and Gemini's terms require a paid service when the app is exposed to users in the EEA, Switzerland or the UK. Archyra only analyses public repositories, discloses this next to the composer, and keeps the providers that say so loudly off by default. A deployment that serves users in those regions, or that wants no third party to see questions, should run on paid or self-hosted providers (one more config block).
- **Rejected:** a second per-provider Java class for each provider (config blocks do the same job with less code); the OpenAI SDK for the generic client (the safety rules need exact control of headers and errors); persisting the daily and monthly counters (not worth a table: the provider's 429 is the backstop).

---

# UI

!Archyra UI Journey_ Code to Intelligence.png

---

# System Architecture

## Overall Architecture

```tsx
                                      ┌──────────────────┐
                                      │     Frontend     │
                                      │ React + TS + Vite│
                                      └────────┬─────────┘
                                               │
                                             REST
                                               │
                               ┌───────────────▼───────────────┐
                               │         API Gateway           │
                               │       Spring Boot API         │
                               └───────────────┬───────────────┘
                                               │
              ┌────────────────────────────────┼──────────────────────────┐
              │                                │                          │
              ▼                                ▼                          ▼
     ┌─────────────────┐             ┌─────────────────┐        ┌────────────────┐
     │ Repository      │             │ Analysis        │        │ User / Account │
     │ Service         │             │ Service         │        │ Service        │
     └────────┬────────┘             └────────┬────────┘        └───────┬────────┘
              │                               │                         │
              ▼                               ▼                         ▼
       GitHub / Git                 Code Intelligence Model       PostgreSQL
                                              │
                              ┌───────────────┴───────────────┐
                              ▼                               ▼
                           Neo4j                           pgvector
                         Code Graph                       AI Context
                              │                               │
                              └──────────────┬────────────────┘
                                             ▼
                                        AI Service
                                             │
                                             ▼
                                            LLM
```

## V0.1

### Architecture

```tsx
GitHub Repository
       │
       ▼
Repository Ingestion
       │
       ├── Clone / Download
       ├── File discovery
       ├── Language detection
       └── Project structure
       │
       ▼
Tree-sitter Parsing
       │
       ▼
AST Analysis
       │
       ▼
Symbol / Reference Analysis
       │
       ▼
Archyra Intermediate Representation
       │
       ├── Files
       ├── Classes
       ├── Interfaces
       ├── Methods
       ├── Functions
       ├── Imports
       ├── Calls
       ├── Inheritance
       └── Dependencies
       │
       ▼
Graph Builder
       │
       ▼
		 Neo4j
       │
       ▼
Architecture / Relationship API
       │
       ▼
	Frontend
```

### APIs

## 1. Analyze repository

### `POST /api/v1/analyses`

Starts an analysis.

### RequestBody

```
{
  "repositoryUrl": "https://github.com/user/project"
}
```

### Response

```
{
  "analysisId": "a8f31c",
  "status": "QUEUED"
}
```

I would **not** make this endpoint wait until the entire repository has been analyzed.

Repository analysis can become expensive.

---

## 2. Check analysis status

### `GET /api/v1/analyses/{analysisId}`

### PathVariable

```
analysisId
```

### Response

```
{
  "analysisId": "a8f31c",
  "status": "COMPLETED",
  "progress": 100
}
```

When the status is `FAILED`, the response also carries a `failureReason` (a user-safe message). The field is present only for `FAILED`; it is omitted for every other status.

```
{
  "analysisId": "a8f31c",
  "status": "FAILED",
  "progress": 25,
  "failureReason": "Repository is too large"
}
```

Possible statuses:

```
QUEUED
CLONING
PARSING
ANALYZING
BUILDING_GRAPH
COMPLETED
FAILED
```

---

# 3. Get repository information

### `GET /api/v1/analyses/{analysisId}/repository`

Returns things such as:

```
{
  "name": "payment-service",
  "languages": [
    "Java",
    "SQL"
  ],
  "files": 143,
  "classes": 87,
  "methods": 412
}
```

Semantics (V0.1):

- Only a `COMPLETED` analysis has a repository: unknown or malformed ids return `404 ANALYSIS_NOT_FOUND`; an analysis that is still running or `FAILED` returns `409 ANALYSIS_NOT_COMPLETED`.
- `languages`: every language found, whether or not it has an extractor (SQL above has no code nodes), most files first. `files` is the number of files in those languages, from PostgreSQL (`analysis_metadata`).
- `name`, `classes` and `methods` come from the graph and count the repository's own code only (external nodes excluded). `classes` counts `Class` nodes (enums and records included, interfaces not). `methods` counts `Method` and `Function` nodes, because languages without classes have functions only.
- `languages` is names only. The per-language file counts, the `analysed` flag and the detected technologies are stored in `analysis_metadata` but not returned yet, so clients cannot show a per-language breakdown or a technologies list. Exposing them here (extending this response) is the planned fix.

---

# 4. Get architecture graph

### `GET /api/v1/analyses/{analysisId}/graph`

This is the basic V0.1 graph.

### Query parameters

```
?nodeType=CLASS
&relationshipType=DEPENDS_ON
```

Both can be optional.

Example:

```
GET /api/v1/analyses/a8f31c/graph?nodeType=CLASS
```

### Response

```
{
  "nodes": [
    {
      "id": "class-123",
      "type": "CLASS",
      "name": "UserService",
      "file": "src/UserService.java",
      "test": false
    }
  ],
  "edges": [
    {
      "source": "class-123",
      "target": "class-456",
      "type": "DEPENDS_ON"
    }
  ]
}
```

Semantics (V0.1):

- Only a `COMPLETED` analysis has a graph: unknown or malformed ids return `404 ANALYSIS_NOT_FOUND`, an analysis that is running or `FAILED` returns `409 ANALYSIS_NOT_COMPLETED`, and a `nodeType` or `relationshipType` that is not an enum value returns `400 VALIDATION_ERROR` listing the allowed values (case-sensitive, upper case).
- The filters are independent. `nodeType` keeps only nodes of that type. `relationshipType` keeps only edges of that type and does not narrow the nodes. An edge is returned only if both of its ends are returned, so `?nodeType=CLASS` gives the classes and the relationships among them, and no edge points at a missing node. With neither filter the response is the whole graph.
- External nodes (types outside the repository) are never returned, and neither are edges that touch them.
- `file` is left out for nodes that have none (repository, module). Nodes and edges are sorted for a stable response.
- `test` is **always present** (never null or omitted): `true` for code declared in a test file and for a module whose files are all tests. It is computed **at read time from the stored `file` path** by `common/TestPathClassifier`, so it is right on every existing analysis without re-analysis, needs no `/graph` query parameter (the `GraphPremiumGate` is unaffected and every plan has it) and the frontend filters on it client-side. It applies to `Class`, `Interface`, `Method`, `Function` and `File` nodes (by their `file`); a `Module` is a test module only if it has at least one file and every file in it is a test file; the `Repository` is never a test. The same field is on `GET /nodes/{nodeId}`, on every item of `GET /files` and on `GET /files/{fileId}`.
- **What counts as a test** (path and file-name conventions only, first match wins; no annotation or framework detection):

  | # | Rule | Examples |
  | --- | --- | --- |
  | 1 | A `src/main/` directory pair is **never** a test (protects a package called `test` inside real code) | `src/main/java/com/x/test/Util.java` → false |
  | 2 | Test source sets: `src/test/`, `src/<name>Test/`, `src/<name>Tests/`, `src/testFixtures/` | `src/integrationTest/java/Flow.java` → true, `src/contest/App.java` → false |
  | 3 | A directory named `test`, `tests`, `__tests__`, `__test__` or `spec` at any depth (exact, lower case) | `app/__tests__/a.js` → true, `testing/a.py`, `docs/specs/a.md` → false |
  | 4 | A C# test project directory ending in `.Tests`, `.Test`, `.UnitTests` or `.IntegrationTests` | `Shop.Tests/OrderTests.cs` → true |
  | 5 | File names (case-sensitive): Java/Kotlin `*Test`, `*Tests`, `*IT`, `Test*`; Python `test_*.py`, `*_test.py`, `conftest.py`; Go `*_test.go`; JS/TS/Vue/Svelte `*.test.*` and `*.spec.*`; Ruby `*_spec.rb`, `*_test.rb`; C# `*Test.cs`, `*Tests.cs`; PHP `*Test.php`; Solidity `*.t.sol`, `*.test.sol`; Rust `*_test.rs` | `pkg/test_foo.py` → true |
  | 6 | Anything else | not a test |

  Limitation: Rust unit tests written inline with `#[cfg(test)]` are not detected. A null or blank path is not a test.
- Premium gate (V0.5): FREE may send no parameter, `nodeType`, `relationshipType` and `level=BEGINNER`; any other parameter or level is `403 PREMIUM_FEATURE`. The gate exists before the features do: `level` and the V2 parameters are accepted for PREMIUM but not implemented, so they return the same graph. Clients treat them as "coming soon" for every plan until they are built.


### `GET /api/v1/analyses/{analysisId}/nodes/{nodeId}`

Returns information about a class/file/function/etc.

```
{
  "id": "class:com.foo.UserService",
  "type": "CLASS",
  "name": "UserService",
  "qualifiedName": "com.foo.UserService",
  "file": "src/UserService.java",
  "line": 12,
  "endLine": 88,
  "kind": "CLASS",
  "parentId": "file:src/UserService.java",
  "test": false,
  "relationships": {
    "outgoing": { "CONTAINS": 4, "DEPENDS_ON": 2 },
    "incoming": { "DEPENDS_ON": 1 }
  }
}
```

Semantics (V0.1):

- `nodeId` is the id the graph exposes. Ids contain `/`, `#`, `(`, `)` and spaces, so a client percent-encodes the whole id (`file%3Asrc%2Fmain%2FApp.java`); the server accepts an encoded slash (`TomcatConfig`).
- Any node type works. Fields that do not apply are left out: `kind` is the declaration kind of a type (`CLASS`, `ENUM`, `RECORD`, `INTERFACE`, ...) and is absent for methods and functions here (the method lists of `GET /files/{fileId}` do carry it, e.g. `CONSTRUCTOR`), `language` replaces it for a file (display name, e.g. `Java`), `file` is absent for repository and module nodes, `parentId` is the containing node (absent for the repository). `qualifiedName` is present for every node type, including files, modules and the repository.
- `line` and `endLine` are the 1-based first and last line of a class, interface, method or function (`endLine` is inclusive and at least `line`). `endLine` is additive and absent for other node types and for analyses made before it was recorded. A type declared in several pieces (C# `partial`, Go receivers, Rust `impl`, Ruby reopened classes) carries the range of its first piece; each piece keeps its own range in the IR, and the methods of the other pieces carry their own file and lines.
- `test` is always present (see the rules under `GET /graph`); for a module it needs the paths of the module's files, read with the node.
- `relationships` counts relationships by type to and from other nodes of the repository. External nodes are not counted, and types with no relationship are absent. `/nodes/{nodeId}/relationships` (V2) will list them.
- An external node (outside the repository), an unknown id and an id that is not a node id all return `404 NODE_NOT_FOUND`. Unknown or malformed analysis ids return `404 ANALYSIS_NOT_FOUND`, an analysis that is not `COMPLETED` returns `409 ANALYSIS_NOT_COMPLETED`.

---

# 6. Get repository files

### `GET /api/v1/analyses/{analysisId}/files`

Query parameters:

```
?language=Java
&path=src/main
&test=false
&page=0
&size=50
```

All optional. Response:

```
{
  "items": [
    { "id": "file:src/main/App.java", "name": "App.java", "path": "src/main/App.java", "language": "Java", "test": false }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 143,
  "totalPages": 3
}
```

Semantics (V0.1):

- The filters are independent and combine with AND; with neither, every file is listed. Files are sorted by path.
- `language` is a display name (`Java`, `C#`) or constant (`CSHARP`), in any case; an unknown value returns `400 VALIDATION_ERROR` listing the allowed ones.
- `path` matches the file with exactly that path and everything below it, on whole segments (`src/main` does not match `src/mainframe`), case-sensitive. Leading `./` or `/` and trailing `/` are ignored. At most 500 characters.
- `test` (`true` = only test files, `false` = only the others, absent = both; any case) combines with `language` and `path` with AND. Any other value returns `400 VALIDATION_ERROR` listing the allowed values. Every item carries a boolean `test` (rules under `GET /graph`).
- Lists paginate (`page` is zero-based, default size 50, at most 200; anything else is `400`). `totalElements` counts the files that match **all** the filters, over all pages. The `test` filter is applied **before** pagination: because the flag is computed from the path rather than stored, a request with `test` fetches the matching rows (id, name, path and language only, bounded by the ingestion file limits), classifies them in Java and pages the result, so `totalElements` and `totalPages` count the files that pass the filter. Without `test` the paging stays in the database query. `/files` has no premium gate.
- Only files that are part of the graph are listed, i.e. files of analysed languages. Languages that are only counted (SQL, shell, ...) have no files here, although they are in `languages` and `files` of `/repository`.
- Unknown or malformed analysis ids return `404 ANALYSIS_NOT_FOUND`, an analysis that is not `COMPLETED` returns `409 ANALYSIS_NOT_COMPLETED`.

---

# 7. Get a file

### `GET /api/v1/analyses/{analysisId}/files/{fileId}`

Returns metadata and extracted entities.

`fileId` is the file's node id from the `/files` listing (`file:src/main/App.java`), percent-encoded like a `nodeId`.

```
{
  "id": "file:src/main/App.java",
  "name": "App.java",
  "path": "src/main/App.java",
  "language": "Java",
  "test": false,
  "module": { "id": "module:com.example", "type": "MODULE", "name": "com.example", "external": false },
  "classes":    [ { "id": "class:com.example.App", "type": "CLASS", "name": "App", "kind": "CLASS", "line": 5, "parentId": "file:src/main/App.java" } ],
  "interfaces": [],
  "methods":    [ { "id": "method:com.example.App#run()", "type": "METHOD", "name": "run", "line": 8, "parentId": "class:com.example.App" } ],
  "functions":  [],
  "imports":    [ { "id": "external:class:java.util.List", "type": "CLASS", "name": "List", "qualifiedName": "java.util.List", "external": true } ],
  "references": [ { "id": "class:com.example.Order", "type": "CLASS", "name": "Order", "qualifiedName": "com.example.Order", "file": "src/main/Order.java", "external": false } ]
}
```

Semantics (V0.1):

- `test` says whether the file is test code (rules under `GET /graph`); the nested lists do not carry it.
- Everything comes from the graph: `classes`, `interfaces`, `methods` and `functions` are the declarations contained in the file (nested types and their methods included, sorted by line); `parentId` says what contains each.
- `imports` are the `IMPORTS` targets: other files of the repository (`type` `FILE`, with `file`) and external types or modules (`external: true`, `qualifiedName` such as `java.util.List`).
- `references` are the `REFERENCES` targets of the file's declarations (field types, signatures, `new`), excluding types declared in the same file. Targets may be external.
- Every list is present and empty when the file has nothing of that kind (a script has no classes). An id that is not a file of the analysis (unknown, a class id, an external node) returns `404 NODE_NOT_FOUND`.

---

# 8. Get a class

### `GET /api/v1/analyses/{analysisId}/classes/{classId}`

Could return:

```
{
  "id": "class-123",
  "name": "UserService",
  "file": "UserService.java",
  "methods": [...],
  "interfaces": [...],
  "dependencies": [...]
}
```

`classId` is the class's node id (`class:com.foo.UserService`), percent-encoded like a `nodeId`. Example response:

```
{
  "id": "class:com.foo.UserService",
  "name": "UserService",
  "qualifiedName": "com.foo.UserService",
  "kind": "CLASS",
  "file": "src/UserService.java",
  "line": 7,
  "parentId": "file:src/UserService.java",
  "superclasses": [],
  "methods":      [ { "id": "method:com.foo.UserService#find(String)", "type": "METHOD", "name": "find", "line": 9 } ],
  "interfaces":   [ { "id": "interface:com.foo.UserApi", "type": "INTERFACE", "name": "UserApi", "qualifiedName": "com.foo.UserApi", "file": "src/UserApi.java", "external": false } ],
  "dependencies": [ { "id": "interface:com.foo.UserRepository", "type": "INTERFACE", "name": "UserRepository", "qualifiedName": "com.foo.UserRepository", "file": "src/UserRepository.java", "external": false } ]
}
```

Semantics (V0.1):

- `/classes` serves `Class` nodes: classes, enums, records and structs, with `kind` telling which. Interfaces are not classes (an interface id returns `404 NODE_NOT_FOUND`); open them with `/nodes/{nodeId}`.
- `methods` are the methods declared directly in the class (not those of nested classes). `interfaces` are the `IMPLEMENTS` targets and `superclasses` the `EXTENDS` targets (a list, because some languages allow several); both may be external (`external: true`, e.g. `java.io.Serializable`). `dependencies` are the `DEPENDS_ON` targets, which are always classes and interfaces of the repository.
- `parentId` is the enclosing class of a nested class, otherwise the declaring file. Every list is present and empty when there is nothing to list.

---

### Database Architecture

### PostgreSQL

For analysis metadata:

```
analyses
repositories
analysis_metadata
```

### Neo4j

For the actual code graph:

```
(:Repository)
(:Module)
(:File)
(:Class)
(:Interface)
(:Method)
(:Function)
```

with relationships such as:

```
IMPORTS
CONTAINS
CALLS
DEPENDS_ON
EXTENDS
IMPLEMENTS
REFERENCES
```

This is the foundation you **do not want to rebuild later**.

## V0.5

### Architecture

```tsx
                         ┌──────────────┐
                         │   Frontend   │
                         └──────┬───────┘
                                │
                         ┌──────▼───────┐
                         │ Spring Boot  │
                         │ API + Auth   │
                         └──────┬───────┘
                                │
            ┌───────────────────┼───────────────────┐
            ▼                   ▼                   ▼
       User Service       Analysis Service      Admin Service
            │                   │                   │
            └───────────────────┼───────────────────┘
                                ▼
                           PostgreSQL
                                │
                                ▼
                              Neo4j
```

### APIs

## Authentication

```
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/logout
```

### `GET /api/v1/auth/me`

Returns the currently authenticated user.

```
{
  "id": "u123",
  "name": "Abhi",
  "email": "..."
}
```

Google OAuth itself can have dedicated auth routes, but I'd let Spring Security handle most of that machinery rather than designing a huge custom authentication API.

#### Implemented in V0.5 (security)

Full spec: `backend/spec/security/spec.md`. How it works, as built:

- **Login methods:** email + password, Google and GitHub (Spring's own `/oauth2/authorization/{google|github}`).
- **Tokens:** a 15-minute access JWT (HS256, `sub` = user id, `roles` claim, never the plan) sent as `Authorization: Bearer ...`, plus an opaque refresh token in an `HttpOnly; SameSite=Strict; Path=/api/v1/auth` cookie, rotated on every use. Presenting an old refresh token revokes the whole login family. Only SHA-256 hashes of refresh, verification and reset tokens are stored.
- **Endpoints under `/api/v1/auth`:** `POST /register`, `/login`, `/refresh`, `/logout`, `/verify-email`, `/resend-verification`, `/forgot-password`, `/reset-password`; `GET /csrf`, `/me`. `/refresh` and `/logout` need the CSRF header (`X-XSRF-TOKEN`, from `GET /csrf`). Register, resend and forgot always answer 202 with the same body, so an email's registration status is never revealed.
- **Registering an address that already has an account** keeps the same `202` and body (a `409 EMAIL_ALREADY_REGISTERED` or an "already registered" message would let anyone test which emails have accounts, so it is deliberately not used). What the owner gets instead happens **asynchronously on the mail executor**, after the response: a *verified* account (password or social) receives an "account already exists" notice with links to `/login` and `/forgot-password` (no token; it names Google/GitHub when the account has no password), at most **one per address per hour** (`RegisterNoticeThrottle`, in memory, keyed by a hash of the address, on top of the per-IP limits); an *unverified* account is a retry of the signup: older verification links are voided and a fresh one is sent, while the stored name and password are **not** replaced (replacing them would let whoever re-registers the address put their own password on the account the real owner then verifies); a *disabled* account gets nothing. Each case writes a `REGISTER_EXISTING_ACCOUNT` audit line with the case and no address or token. The frontend's check-your-inbox screen is worded so it is true for new and existing addresses alike.
- **Social login** ends with a redirect to `{frontend-base-url}/auth/callback` (or `/login?error=<code>`); the SPA's session boot then calls `/auth/refresh` like after any login (the callback page itself never refreshes; see the session-boot decision below). No token ever appears in a URL. A handshake started from Settings is a *link*, not a login, and ends at `/settings` instead (see Linked sign-in methods).
- **Ownership:** an analysis belongs to the user who started it, and to nobody else (admins included). Anything not yours is a 404, so ids cannot be probed.
- **Premium:** enforced on the server from the database plan (never the token). On `GET .../graph`, FREE gets no parameters, `nodeType`, `relationshipType` and `level=BEGINNER`; everything else is `403 PREMIUM_FEATURE`.
- **Errors** keep the `{ code, message }` shape. The complete set the backend emits:
    - 400: `VALIDATION_ERROR` (lists allowed values for an enum parameter), `INVALID_REQUEST_BODY` (missing or malformed JSON), `INVALID_OR_EXPIRED_TOKEN` (verification or reset link)
    - 401: `AUTHENTICATION_REQUIRED` (with `WWW-Authenticate: Bearer`), `INVALID_CREDENTIALS`, `REFRESH_TOKEN_INVALID` (refresh failed; the cookie is cleared)
    - 403: `ACCESS_DENIED`, `CSRF_TOKEN_INVALID`, `EMAIL_NOT_VERIFIED`, `ACCOUNT_DISABLED`, `PREMIUM_FEATURE`, `USAGE_LIMIT_EXCEEDED`
    - 404: `ANALYSIS_NOT_FOUND`, `NODE_NOT_FOUND`, `USER_NOT_FOUND`, `IDENTITY_NOT_FOUND`
    - 409: `ANALYSIS_NOT_COMPLETED`, `ANALYSIS_IN_PROGRESS`, `CANNOT_DISABLE_SELF`, `LAST_ADMIN`, `PROVIDER_ALREADY_LINKED`, `LAST_LOGIN_METHOD`
    - 429: `RATE_LIMITED` (with `Retry-After` in seconds); 503: `SERVICE_BUSY`
- **Request and response bodies:** `POST /register` takes `{ name, email, password }`; `POST /login` takes `{ email, password }` and returns `{ accessToken, tokenType: "Bearer", expiresIn }` (seconds); `/refresh` returns the same. `POST /verify-email` takes `{ token }`; `/resend-verification` and `/forgot-password` take `{ email }`; `/reset-password` takes `{ token, newPassword }`. Register, verify, resend, forgot and reset answer `{ message }`. `GET /csrf` returns `{ headerName: "X-XSRF-TOKEN", token }`.
- **Passwords** are hashed with the delegating encoder (bcrypt), so they are limited to 8 to 72 **bytes** (not the 8 to 128 characters of the security spec; the validation message still says 128 characters). A password equal to the email or on a short common list is refused.
- **Deployment notes:** the SPA and the API must share a registrable domain (the refresh cookie is `SameSite=Strict`); behind a proxy use `server.forward-headers-strategy=native`.


- **Linking Google/GitHub from Settings** (`/users/me/linked-identities`, below) is a separate flow from login. The login resolver is unchanged (it still matches by `(provider, providerUserId)`, then by verified email); a link never looks an account up by email.

---

# User

### `GET /api/v1/users/me`

Get current user.

### `PATCH /api/v1/users/me`

### RequestBody

```
{
  "name": "Abhi"
}
```

> **Built in V0.5:** `GET` and `PATCH` (name only: 1 to 100 characters, no control characters) return the same profile as `GET /auth/me`. `plan` is the effective plan (an expired PREMIUM reads as FREE); `role` is what the frontend's admin gate reads:
>
> ```
> {
>   "id": "6f1c…",
>   "name": "Abhi",
>   "email": "…",
>   "role": "USER",
>   "plan": "FREE",
>   "emailVerified": true,
>   "linkedProviders": ["GITHUB"]
> }
> ```

### Linked sign-in methods (Settings)

> **Built:** `GET /api/v1/users/me/linked-identities`, `POST /api/v1/users/me/linked-identities/{provider}/link-intent` and `DELETE /api/v1/users/me/linked-identities/{provider}` (`{provider}` is `google` or `github`, lower case; anything else is `400 VALIDATION_ERROR`). All need a bearer token; `GET /users/me` and its `linkedProviders` are unchanged.
>
> ```
> GET  → { "hasPassword": true, "identities": [ { "provider": "google", "email": "…"|null, "login": null, "linkedAt": "2026-11-19T…Z" } ] }
> POST → { "authorizationUrl": "/oauth2/authorization/google?link_intent=<token>" }   (+ Set-Cookie: link_binding=…)
> DELETE → 204
> ```
>
> - **`hasPassword`** is `users.password_hash IS NOT NULL`. That is reliable: accounts created by a social login have a null hash (never a placeholder), and a social takeover of an unverified account clears it. Sign-in with an unusable dummy hash exists only inside `AppUserDetailsService`.
> - **Starting a link.** `link-intent` creates an `oauth_link_intents` row (`token_hash` unique, `binding_hash`, `user_id`, `provider`, `created_at`, `expires_at` 5 minutes, `consumed_at`): two random 256-bit secrets, only their SHA-256 hashes stored. The token goes in the URL; the binding secret goes in an `HttpOnly; SameSite=Strict; Path=/oauth2/authorization; Max-Age=300` cookie (`link_binding`, `Secure` unless `COOKIE_SECURE=false`). Already linked is `409 PROVIDER_ALREADY_LINKED`; limited per user by `app.security.rate-limit.link-intent-per-user-per-hour` (default 10, `429 RATE_LIMITED`). Expired and used rows are purged lazily and daily.
> - **Browser binding.** `LinkAuthorizationFilter` runs before Spring's authorization-redirect filter on `GET /oauth2/authorization/{provider}`. With `link_intent` it requires a valid, unexpired, unused intent **for that provider** and a matching binding cookie, then consumes the intent, copies `{userId, provider, expiresAt}` into a fresh handshake session and deletes the cookie. Otherwise it redirects to `{frontend-base-url}/settings?link_error=link_expired`. Without this, an attacker could send a victim a crafted `link_intent` URL that attaches the **victim's** Google/GitHub identity to the **attacker's** account. A request without `link_intent` clears any link context left in the session.
> - **Link mode.** The presence of that session context is the only thing that makes the callback a link. The user-info services then return a `LinkPrincipal` (provider id, email, GitHub login) and **never call the account resolver**, so nothing is created, matched by email or taken over; the provider's email need not be verified (it is display-only and never changes the account email). `OAuth2LinkHandler` resolves the user strictly from the session, issues no tokens or cookies, destroys the session, and redirects to `/settings?link=ok&provider=<p>` or `/settings?link_error=<code>`, where `<code>` is one of `oauth_cancelled`, `oauth_failed`, `oauth_account_disabled`, `identity_already_linked` (the identity belongs to another account; nothing changes and the owner is not named), `provider_already_linked` (this user already has a different identity of that provider), `link_expired` (also re-checked against the deadline stored in the session). Linking the same identity again is a success.
> - **Schema:** `linked_identities` gains nullable `provider_login` and a unique `(user_id, provider)`; `provider_email` becomes nullable (a startup step drops the old NOT NULL, because `ddl-auto=update` never relaxes a constraint). `created_at` was already there and is the "linked on" date.
> - **Unlink.** `404 IDENTITY_NOT_FOUND` if not linked; `409 LAST_LOGIN_METHOD` if it would leave the account with no password and no other identity. Link and unlink both take a pessimistic lock on the user row, so two concurrent unlinks cannot both pass that check.
> - **Notification emails.** A link and an unlink each send one notice (provider, time, a "wasn't you? reset your password" link to `/forgot-password`, no token) **after the transaction commits**; a mail failure is logged and never fails the operation.
> - **Set a password** for an OAuth-only user is not built; they use Forgot password, which works for an account without a password (`AccountEmailFlows.forgotPassword` only needs an enabled account, and the reset sets the first password).
> - Link and unlink never touch the refresh cookie or refresh-token families.

### Change password (Settings)

> **Built:** `POST /api/v1/users/me/password` (bearer token).
>
> ```
> POST { "currentPassword": "…", "newPassword": "…" }
> 200  → { "accessToken": "…", "tokenType": "Bearer", "expiresIn": 900 }   (+ Set-Cookie: refresh_token=…)
> ```
>
> - **The current password is required**, even with a valid access token, so a stolen token alone cannot take the account over. A wrong one is `400 INCORRECT_PASSWORD` and counts like a failed login: the same lockout (5 attempts, 15 minutes), so the form cannot be used to guess the password. While the account is locked the current password is not checked and the answer is the same `INCORRECT_PASSWORD`.
> - **The new password** follows `PasswordPolicy` (`400 VALIDATION_ERROR`) and must differ from the current one. Messages never echo either password.
> - **Sessions.** One transaction sets the new hash (and clears any lockout), revokes every refresh-token family of the user and starts a new one for the caller, whose cookie the response replaces. The browser that changed the password stays signed in; every other device has to sign in again once its access token (15 minutes, stateless) runs out.
> - **No password yet** (an account that only signs in with Google or GitHub): `409 NO_PASSWORD_SET`. Setting a *first* password needs proof of the inbox, so it stays on Forgot password. The Settings section shows that instead of the form when `linked-identities.hasPassword` is false.
> - **Notice email** (`EmailService.sendPasswordChangedNotice`: when, "wasn't you? reset your password" link to `/forgot-password`, no token) after the commit; a mail failure is logged and never fails the change. Audit: `PASSWORD_CHANGED`, `PASSWORD_CHANGE_REFUSED`, `ACCOUNT_LOCKED`.
> - No dedicated rate limit: the lockout bounds guessing. Code: `auth/PasswordController`, `auth/PasswordChangeService`.

---

# Analysis history

This is what I previously called repository management.

### `GET /api/v1/analyses`

Query parameters:

```
?page=0
&size=20
&status=COMPLETED
```

Returns the user's previous analyses.

### `DELETE /api/v1/analyses/{analysisId}`

Deletes an analysis.

### `POST /api/v1/analyses/{analysisId}/reanalyze`

Starts another analysis of the repository.

Returns `202 { "analysisId", "status" }`, the same shape as `POST /api/v1/analyses`. `analysisId` is the id of the **new** analysis.

This is enough. You don't need a massive "repository management" subsystem.

> **Built in V0.5:** `GET /api/v1/analyses` (the caller's own, newest first, `page` and `size` (default 50); no `status` filter yet) and `DELETE /api/v1/analyses/{analysisId}` (204; 409 `ANALYSIS_IN_PROGRESS` while it runs). The list returns:
>
> ```
> {
>   "items": [
>     { "analysisId": "a8f31c…", "repositoryUrl": "https://github.com/user/project", "status": "COMPLETED", "progress": 100, "createdAt": "2026-11-18T07:36:26Z" }
>   ],
>   "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
> }
> ```
>
> Items carry no repository name or languages; neither does `GET /analyses/{analysisId}`, which has no `repositoryUrl` either, so a client that opens an analysis directly finds its URL in the history list. 
>
> **Built in V0.5:** `POST /api/v1/analyses/{analysisId}/reanalyze`.
>
> - **Same path as a new analysis.** It reads the repository URL of the caller's own analysis (anything else is 404 `ANALYSIS_NOT_FOUND`) and goes through `AnalysisRegistrar`, so it uses the same per-user lock, rate limit (`RATE_LIMITED`), usage charge (403 `USAGE_LIMIT_EXCEEDED` for a free user at the limit, never for PREMIUM) and executor rejection handling (503 `SERVICE_BUSY`, the new analysis is marked `FAILED` and its unit returned) as `POST /analyses`. No plan gate: every plan can re-run.
> - **A new analysis, then a replacement.** The re-run is a new analysis with a new id (`analyses.replaces_analysis_id` points at the old one). When it reaches `COMPLETED`, the old analysis (graph, metadata and row) is deleted, for every plan, since an analysis costs no AI or other paid resource. Clients should switch to the new id. A failed or rejected re-run leaves the old analysis and its results untouched. If deleting the old one fails, it stays and the user can delete it. Deleting it returns its credit to a free user, like any deletion (see Usage), so a completed re-run costs a free user nothing in the end.
> - **Concurrency and duplicates.** If the repository is already being analysed by this user (the analysis itself or a newer one, in `QUEUED` through `BUILDING_GRAPH`), the running analysis is returned, free of charge and with no new pipeline. Parallel re-runs of one analysis therefore produce one new analysis, one charge and one pipeline. Re-running a `FAILED` analysis is a retry that replaces the failed record.

---

# Usage

### `GET /api/v1/usage`

Authenticated. The user is always the caller (taken from the security context): there is no `userId` parameter, and nothing in a request can change a counter. Reading never creates rows or takes locks; a user with no row sees `used: 0`. Returns one entry for every usage type, `AI_QUERY` included, so the frontend does not change when the AI chat arrives:

```
{
  "plan": "FREE",
  "usage": [
    { "usageType": "REPOSITORY_ANALYSIS", "used": 3, "limit": 5,  "remaining": 2,  "limitReached": false },
    { "usageType": "AI_QUERY",            "used": 0, "limit": 20, "remaining": 20, "limitReached": false }
  ]
}
```

For a PREMIUM user `limit` and `remaining` are `null` (present, not omitted, not `-1`) and `limitReached` is `false`; `used` is still the true count:

```
{ "usageType": "REPOSITORY_ANALYSIS", "used": 12, "limit": null, "remaining": null, "limitReached": false }
```

`remaining = max(limit - used, 0)` and `limitReached = used >= limit` for FREE. `plan` is the effective plan (an expired PREMIUM shows `FREE`). There is no `resetAt`: the allowance is for life.

> **Built in V0.5** (`usage/` package):
>
> - **Lifetime allowance.** A free user gets `archyra.usage.free-tier.repository-analysis-limit` analyses (default 5) in total, not per month, and nothing resets. The counter is `used_count` in `user_usage`, one row per user and usage type, created on first use. The limits come from configuration only (`archyra.usage.free-tier.*`; a missing or negative value fails startup). `AI_QUERY` counts Ask AI questions (see Ask AI): a unit is reserved before the model is called and returned once if the answer fails; cached answers are free; `AI_LIMIT` (default 20) is the FREE allowance.
> - **When a unit is consumed.** `AnalysisService.createAnalysis` calls `AnalysisRegistrar.registerOrReuse`, one short transaction that commits before the background pipeline starts. Every analysis creation goes through it (`reanalyze` uses the same registrar).
> - **Locking.** The transaction first creates the user's counter row if needed (`INSERT ... ON CONFLICT DO NOTHING`, so concurrent first requests cannot violate the unique constraint) and then locks it with `SELECT ... FOR UPDATE` (waiting at most 5 s, then `503 SERVICE_BUSY`). That serializes all analysis creation of one user, and only that user, and makes the duplicate check, the limit check, the increment and the insert race-free. Premium users take the same lock so that their counter stays exact. A guarded `UPDATE ... WHERE used_count < :limit` would solve the limit race but not the duplicate-request race, which needs the same serialization anyway.
> - **Duplicates.** Under that lock, a request for a repository URL (compared exactly as validated) that the same user already has in `QUEUED`, `CLONING`, `PARSING`, `ANALYZING` or `BUILDING_GRAPH` returns that analysis (same `202 { analysisId, status }` shape), starts no pipeline and charges nothing. This check runs before the limit check, so a user at 5/5 who resubmits a running URL gets it back instead of a 403. A `COMPLETED` or `FAILED` analysis is not a duplicate: running it again is a new charge.
> - **Limit.** A FREE user with `used_count >= limit` gets `403 USAGE_LIMIT_EXCEEDED` (`{ "code", "message" }`, the message names the configured limit) and no analysis row is created. 403 matches `PREMIUM_FEATURE`: a lifetime limit is not a "retry later" situation, which is what the `429` of `RATE_LIMITED` means.
> - **Premium.** A PREMIUM user (from `User.effectivePlan`, read from the database, never the token) is never blocked, and the counter is still kept. Premium feature gating (`PREMIUM_FEATURE`) is unchanged.
> - **Downgrade (intentional).** `used_count` is everything the user ever consumed. A former premium user with 12 analyses who is back on FREE is already over the limit: `used: 12, limit: 5, remaining: 0, limitReached: true`, and blocked until the limit is raised.
> - **Deleting gives the credit back (FREE users).** When an analysis is deleted (`DELETE /analyses/{id}`, or replaced by a completed re-run), a user who is held to the free limit (FREE, or an expired PREMIUM) gets its credit back: `used` goes from 4 to 3 and they can analyze another repository. It is the same guarded refund as above, in the same transaction as the row delete, so it returns at most one credit per analysis: an analysis that did not consume one (a `FAILED` one, whose credit already came back, one from before usage tracking, or one that was never charged) returns nothing, and neither does deleting it again. A PREMIUM user's delete returns nothing, so their recorded count stays true if they later downgrade. The counter therefore equals the number of kept, charged analyses of a free user. Re-running at the limit is still refused, because the new analysis is charged before the old one is replaced.
> - **Refunds.** A unit goes back exactly once when the pipeline ends an analysis as `FAILED` (every failure path), when the executor rejects the submission (`503 SERVICE_BUSY`; the row stays as `FAILED`), and, at startup, for analyses interrupted by a restart or failed with a refund that did not go through. `FAILED` and the refund are written in one transaction, so a client that sees `FAILED` already has its unit back. `analyses.quota_charged` and `quota_refunded` make it idempotent: `UPDATE analyses SET quota_refunded = true WHERE id = ? AND quota_charged AND NOT quota_refunded` lets only the first caller go on to the guarded `used_count - 1` (`used_count > 0`, plus a `used_count >= 0` check constraint). A `COMPLETED` analysis is never refunded by the pipeline. Analyses from before this feature have `quota_charged = false`, so they can never trigger a refund, and users start at `used = 0` (no backfill).

---

# Admin APIs

These should be protected by an `ADMIN` role.

### `GET /api/v1/admin/users`

Query parameters:

```
?page=0
&size=50
```

> **Built in V0.5:** pages of `{ items, page, size, totalElements, totalPages }` (newest accounts first, `size` 1 to 200). Each item, also returned by `GET /admin/users/{userId}` and the status `PATCH`:
>
> ```
> {
>   "id": "6f1c…", "name": "…", "email": "…", "role": "USER", "plan": "FREE", "planExpiresAt": null,
>   "emailVerified": true, "enabled": true, "locked": false,
>   "createdAt": "2026-11-17T10:00:00Z", "lastLoginAt": "2026-11-18T07:00:00Z", "linkedProviders": []
> }
> ```
>
> `planExpiresAt` and `lastLoginAt` are `null` when unset. An unknown user id is `404 USER_NOT_FOUND`.

### `GET /api/v1/admin/stats`

Returns things like:

```
{
  "totalUsers": 1240,
  "activeUsers": 438,
  "premiumUsers": 82,
  "totalAnalyses": 3612
}
```

> **Built in V0.5:** each value is a database `count` query. `activeUsers` is enabled accounts that signed in during the last 30 days (`lastLoginAt`), `premiumUsers` counts PREMIUM plans that have not expired (same rule as `User.effectivePlan`), and `totalAnalyses` counts every analysis whatever its status. ADMIN only.

### `GET /api/v1/admin/ai/providers`

ADMIN only (the route rule, re-checked in the database like the other admin routes). The state of the Ask AI provider chain, every known provider in chain order (listed ones first, then the opt-in ones):

```
[ { "id": "groq", "displayName": "Groq", "configured": true, "enabled": true, "state": "AVAILABLE",
    "requestsToday": 12, "requestsThisMonth": 340 },
  { "id": "openrouter", "displayName": "OpenRouter", "configured": true, "enabled": true, "state": "COOLING_DOWN",
    "coolingUntil": "2026-11-19T10:30:00Z", "requestsToday": 50, "requestsThisMonth": 50 },
  { "id": "cohere", "displayName": "Cohere", "configured": false, "enabled": true, "state": "DISABLED",
    "requestsToday": 0, "requestsThisMonth": 0 } ]
```

`state` is `AVAILABLE`, `COOLING_DOWN` (with `coolingUntil`), `RATE_LIMITED_LOCALLY` (one of its own limits is used up) or `DISABLED` (not enabled or not configured: not in the chain). The request counters are in memory and reset on restart. Never a key, a key fragment or a URL.

### `GET /api/v1/admin/users/{userId}`

### `PATCH /api/v1/admin/users/{userId}/status`

RequestBody:

```
{
  "enabled": false
}
```

> **Built in V0.5:** `GET /admin/users`, `GET /admin/users/{userId}` and this `PATCH`. Disabling ends all of the user's sessions, and an admin cannot disable themselves or the last enabled admin (409 `CANNOT_DISABLE_SELF` / `LAST_ADMIN`). `GET /admin/stats` is built (below); `GET /usage` is built (see Usage). Also built: `GET` and `PATCH /api/v1/users/me` (name only).

---

## V1

### Architecture

```tsx
                         User Question
                              │
                              ▼
                         Spring Boot
                              │
                              ▼
                         AI Service
                         /         \
                        /           \
                       ▼             ▼
                  Neo4j Graph     pgvector
                       │             │
                       └──────┬──────┘
                              ▼
                         Context Builder
                              │
                              ▼
                             LLM
                              │
                              ▼
                          AI Answer
```

### APIs

## Ask AI

All routes need a bearer token. An analysis is visible only to its owner (404 for anything else, admins included) and conversations belong to the user who created them (`404 CONVERSATION_NOT_FOUND`, admins included). Lists paginate with zero-based `page` and `size` (default 50, at most 200; out of range is `400 VALIDATION_ERROR`). Errors are `{ code, message }`; messages never echo user input or a provider's response.

### `GET /api/v1/analyses/{analysisId}/ai-index`

Whether Ask AI can answer about this analysis. `409 ANALYSIS_NOT_COMPLETED` while it runs.

```
{ "status": "READY", "chunkCount": 412, "embeddedCount": 412, "embeddingModel": "all-MiniLM-L6-v2",
  "retryable": false, "reason": null, "indexedAt": "2026-11-19T10:00:00Z" }
```

`status` is `NOT_INDEXED` (analysed before Ask AI existed: show "Re-analyze to enable Ask AI"), `INDEXING`, `READY`, `FAILED` or `EMPTY` (only test code). `retryable` is true only for `FAILED` with chunks. `reason` is a safe message for `FAILED` and `EMPTY`, or a note on a `READY` index that covers only part of a large repository.

### `POST /api/v1/analyses/{analysisId}/ai-index`

Retries a `FAILED` index for free. `202` with the same body when it starts, `200` unchanged for `INDEXING`, `READY` and `EMPTY`, `409 AI_REANALYZE_REQUIRED` for `NOT_INDEXED` and for a `FAILED` index with no chunks (a chunk-capture failure needs a new analysis).

### `POST /api/v1/analyses/{analysisId}/chat`

```
{ "message": "How does authentication work in this repository?", "conversationId": "...optional..." }
```

`message` is 1 to 2000 characters after trimming. Without `conversationId` a conversation is created, titled from the first 60 characters of the question (cut at a word).

```
{
  "conversationId": "7c0e...", "messageId": "b19a...",
  "answer": "Login goes through [1], which delegates to [2]...",
  "sources": [
    { "ref": 1, "type": "CLASS", "id": "class:com.foo.SecurityConfig", "name": "SecurityConfig",
      "file": "src/main/java/com/foo/SecurityConfig.java", "line": 12, "endLine": 88, "excerpt": "...first lines of the chunk..." },
    { "ref": 2, "type": "METHOD", "id": "method:com.foo.AuthService#login(String)", "name": "login",
      "file": "...", "line": 41, "endLine": 77, "excerpt": null }
  ],
  "cached": false, "provider": "gemini", "providerName": "Gemini"
}
```

`answer` is Markdown (never HTML). `ref` matches the `[n]` markers in `answer`, already renumbered by first appearance; `excerpt` is the first lines of the chunk the model saw, or `null` for a node known only from a graph fact; a graph fact cites both its nodes (one source per node, under the ref that first cited it). Every source id is a node of this analysis, verified in Neo4j before the response is sent; a citation the model invented is dropped, and an answer with no citations has `sources: []`. `provider` is the id of the provider that answered (free-form: the chain is configuration, never switch on it) and `providerName` its display name, for people. `cached: true` means the same question in the same situation was answered before: nothing was charged and `provider` is the one that answered then.

Errors: `400 VALIDATION_ERROR` (blank or too long message, malformed `conversationId`), `403 USAGE_LIMIT_EXCEEDED` (a FREE user out of `AI_QUERY`), `404 ANALYSIS_NOT_FOUND`, `404 CONVERSATION_NOT_FOUND` (not yours, another analysis, deleted), `409 ANALYSIS_NOT_COMPLETED`, `409 AI_NOT_READY` (read `GET ai-index` for the state), `409 CONVERSATION_FULL` (100 messages), `409 CONVERSATION_BUSY` (a question of this conversation is still being answered), `409 TOO_MANY_CONVERSATIONS` (50 per analysis), `429 RATE_LIMITED` (10 questions a minute per user, every plan, with `Retry-After`), `503 AI_UNAVAILABLE` (no provider usable, meaning none configured, all cooling down or all at a local limit, or every attempt failed; the question was not counted and the frontend keeps the user's text), `503 SERVICE_BUSY`. Everything refused before the model is called costs nothing.

---

# Conversation history

### `GET /api/v1/analyses/{analysisId}/conversations`

A page of `{ "id", "title", "createdAt", "updatedAt", "messageCount" }`, newest activity first. `messageCount` counts answered questions and their answers.

### `POST /api/v1/analyses/{analysisId}/conversations`

Empty body. `201 { "id", "title": "New conversation", "createdAt", "updatedAt", "messageCount": 0 }`. Needs a `COMPLETED` analysis; `409 TOO_MANY_CONVERSATIONS` at the limit. The title is set automatically on the first question; there is no rename in V1.

### `DELETE /api/v1/conversations/{conversationId}`

`204`; another user's or an unknown id is `404 CONVERSATION_NOT_FOUND`; a conversation with a question being answered is `409 CONVERSATION_BUSY`. Deleting never gives questions back: the allowance is for life.

### `GET /api/v1/conversations/{conversationId}/messages`

A page, oldest first, of `{ "id", "role": "USER|ASSISTANT", "content", "sources", "provider", "providerName", "cached", "createdAt" }`; `sources`, `provider` and `providerName` exist only on assistant messages, and a question that is still being answered is never listed.

---

# Architecture explanation

### `GET /api/v1/analyses/{analysisId}/architecture`

Returns the AI-generated interpretation of the architecture.

For example:

```
{
  "architecture": "Layered Architecture",
  "confidence": 0.91,
  "description": "...",
  "layers": [...]
}
```

The confidence/evidence concept is important because Archyra shouldn't pretend that architecture detection is mathematically certain.

---

# AI file/class explanation

### `POST /api/v1/analyses/{analysisId}/explain`

### RequestBody

```
{
  "nodeId": "class-123",
  "level": "INTERMEDIATE"
}
```

This also gives you a foundation for V3.

### Frontend

```tsx
Dashboard
   │
   └── Repository
          │
          ├── Overview
          ├── Architecture
          ├── Files
          ├── Relationships
          └── Ask AI
```

> **Built in V0.5 (ahead of V1):** this structure exists in `frontend/` against the real API: dashboard (submit, history, usage), the polling progress view, the five repository tabs (Ask AI, which was a "coming soon" page then and is now the working chat described below), settings, and the admin area (`/admin/stats`, `/admin/users`, `/admin/users/{userId}`, shown only when `/users/me` says `ADMIN`). The Vite dev server proxies `/api`, `/oauth2` and `/login/oauth2` (keeping the browser's `Host`, adding `X-Forwarded-*`), so in development Google and GitHub redirect to `http://localhost:5173/login/oauth2/code/{google,github}` (verified from the real authorization redirect; `OAuth2RedirectUriHttpTest` and an e2e test guard it). Those URIs must be what the providers have registered: a **Google** web client can list several authorized redirect URIs (add the `:5173` one next to any `:8080` one), but a **GitHub OAuth App holds exactly one** callback URL, so it can serve the proxy flow *or* direct `:8080` use, not both (use a second OAuth App and separate `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` in `backend/.env` if both origins are ever needed). Always browse via `http://localhost:5173`, never `127.0.0.1`. A `redirect_uri_mismatch` is a provider-console setting, not a code defect. The session boot (csrf → refresh → `/users/me`, one single flight per page load with a 10 s timeout and a retry screen) is the only code that hydrates a session, including after a Google/GitHub redirect. Settings has a *Sign-in methods* section to link and unlink Google and GitHub, and a *Password* section to change the password. The **Ask AI tab is built** against the real backend (index panels, conversations in `?c=`, grounded answers with expandable sources, the `AI_QUERY` usage chip, failure handling by error code, and a safe in-house Markdown renderer with no HTML injection). Details: `frontend/CLAUDE.md`.

## V2

### Architecture

```tsx
                         Neo4j
                           │
                           ▼
                    Graph Query Engine
                           │
                    ┌──────┴──────┐
                    ▼             ▼
              Class Graph     Method Graph
                    │             │
                    └──────┬──────┘
                           ▼
                       REST API
                           │
                           ▼
                      React Flow
```

```tsx
Frontend
   │
   │ "Expand UserService"
   ▼
Backend
   │
   ▼
Neo4j traversal
   │
   ▼
Relevant subgraph
   │
   ▼
Frontend
```

### APIs

## Get interactive graph

### `GET /api/v1/analyses/{analysisId}/graph`

This endpoint already exists from V0.1.

**Don't create another endpoint just because V2 arrived.**

Instead, extend it.

For example:

```
GET /api/v1/analyses/{id}/graph
    ?rootNodeId=class-123
    &depth=2
    &relationshipTypes=CALLS,DEPENDS_ON
```

Now the graph can be dynamically generated.

---

# Get relationships of a node

### `GET /api/v1/analyses/{analysisId}/nodes/{nodeId}/relationships`

Query parameters:

```
?direction=OUTGOING
&type=CALLS
&depth=1
```

This is extremely useful for V2.

---

# Expand node

You could even have:

### `GET /api/v1/analyses/{analysisId}/nodes/{nodeId}/expand`

Query parameters:

```
?depth=1
&relationshipTypes=CALLS,DEPENDS_ON
```

Response:

```
{
  "centerNode": {...},
  "nodes": [...],
  "edges": [...]
}
```

---

# Class methods

### `GET /api/v1/analyses/{analysisId}/classes/{classId}/methods`

Returns the methods belonging to a class.

---

# Method dependencies

### `GET /api/v1/analyses/{analysisId}/methods/{methodId}/dependencies`

Query parameters:

```
?direction=BOTH
&depth=2
```

This powers your idea of:

> Click class → see methods → click method → see its dependencies/calls.
>

---

# Graph filters

### `GET /api/v1/analyses/{analysisId}/graph`

Potential query parameters:

```
rootNodeId
depth
nodeTypes
relationshipTypes
direction
maxNodes
```

For example:

```
?rootNodeId=class-123
&depth=2
&nodeTypes=CLASS,METHOD
&relationshipTypes=CALLS,DEPENDS_ON
&direction=BOTH
```

That one endpoint could power a **lot** of V2.

## V3

### Architecture

```tsx
                     Code Intelligence Model
                              │
                              ▼
                       Graph Abstraction
                              │
                 ┌────────────┼────────────┐
                 ▼            ▼            ▼
             Beginner     Intermediate    Expert
                 │            │            │
                 └────────────┼────────────┘
                              ▼
                        Graph Response
```

### APIs

## Get expertise-aware graph

### `GET /api/v1/analyses/{analysisId}/graph`

Again, we extend the existing endpoint.

Query:

```
?level=BEGINNER
```

or:

```
?level=INTERMEDIATE
```

or:

```
?level=EXPERT
```

---

# Beginner

```
GET /api/v1/analyses/123/graph?level=BEGINNER
```

Could return:

```
Frontend
   ↓
API
   ↓
Business Logic
   ↓
Database
```

But it should still be based on the actual repository.

---

# Intermediate

```
GET /api/v1/analyses/123/graph?level=INTERMEDIATE
```

Now show actual architectural components and important relationships.

---

# Expert

```
GET /api/v1/analyses/123/graph?level=EXPERT
```

Potentially expose:

- classes
- interfaces
- methods
- calls
- dependencies
- inheritance
- implementations
- external services
- database interactions

---

# Premium enforcement

This is important.

**Don't trust the frontend to enforce this.**

Bad:

```
Frontend:
if premium:
    show expert graph
```

Anyone can modify that.

Instead:

```
GET /graph?level=EXPERT
             │
             ▼
       Spring Security
             │
             ▼
     Subscription Service
             │
       ┌─────┴─────┐
       │           │
      FREE       PREMIUM
       │           │
       X           ▼
    403 Forbidden   Graph
```

For example:

```
GET /api/v1/analyses/123/graph?level=EXPERT
```

Free user:

```
403 Forbidden
```

with something like:

```
{
  "code": "PREMIUM_FEATURE",
  "message": "Expert graph requires Archyra Premium."
}
```

---

# V3 AI + expertise

You can extend the existing AI endpoint too:

### `POST /api/v1/analyses/{analysisId}/chat`

```
{
  "message": "Explain the authentication flow",
  "expertiseLevel": "BEGINNER"
}
```

Then:

```
BEGINNER
→ conceptual explanation

INTERMEDIATE
→ architecture + important classes

EXPERT
→ detailed implementation + relationships
```

So your graph and AI use the **same expertise concept**.

That's much more elegant than building two separate systems.

---

# 

## Final API Structure

By V3, your API could look roughly like this:

```
/api/v1

├── /auth
│
├── /users
│   ├── /me/linked-identities (GET, POST /{provider}/link-intent, DELETE /{provider})
│   └── /me/password (POST)
│
├── /analyses
│   ├── POST /
│   ├── GET /
│   ├── GET /{analysisId}
│   ├── DELETE /{analysisId}
│   ├── POST /{analysisId}/reanalyze
│   │
│   ├── GET /{analysisId}/repository
│   ├── GET /{analysisId}/files
│   ├── GET /{analysisId}/files/{fileId}
│   │
│   ├── GET /{analysisId}/classes/{classId}
│   ├── GET /{analysisId}/classes/{classId}/methods
│   │
│   ├── GET /{analysisId}/graph
│   ├── GET /{analysisId}/nodes/{nodeId}
│   ├── GET /{analysisId}/nodes/{nodeId}/relationships
│   ├── GET /{analysisId}/nodes/{nodeId}/expand
│   │
│   ├── GET /{analysisId}/methods/{methodId}/dependencies
│   │
│   ├── GET /{analysisId}/architecture
│   │
│   ├── GET /{analysisId}/ai-index
│   ├── POST /{analysisId}/ai-index
│   ├── POST /{analysisId}/chat
│   ├── GET /{analysisId}/conversations
│   ├── POST /{analysisId}/conversations
│   └── POST /{analysisId}/explain
│
├── /conversations
│   ├── DELETE /{conversationId}
│   └── GET /{conversationId}/messages
│
├── /usage
│
└── /admin
    ├── /users
    ├── /users/{userId}
    └── /stats
```
