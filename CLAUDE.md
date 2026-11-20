# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Archyra ("the architecture behind your code") analyzes a GitHub repository, builds a code graph (files, classes, methods, imports, calls, inheritance) and renders it as an explorable architecture diagram. AI explanations come later, on top of that graph.

`Architecture.md` is the design source of truth for the roadmap, API contracts and data model. Read the relevant version section before implementing a feature, and update it when a design decision changes.

## Repository layout

- `backend/`: Spring Boot API and analysis engine. Build commands, package conventions and backend rules are in `backend/CLAUDE.md`.
- `frontend/`: the React app, built and talking to the real `/api/v1` backend through the Vite proxy (no mock data). Commands, structure and UI rules are in `frontend/CLAUDE.md`.

## Commands (details in the two sub-guides)

```bash
cd backend  && ./mvnw clean test                      # all backend tests (Docker needed; use `clean` after signature changes)
cd backend  && ./mvnw test -Dtest=AccountLinkingIntegrationTest   # one class (add #method for one test)
cd frontend && npm run verify                         # typecheck + lint + check:tokens + unit tests + build + session-boot e2e
cd frontend && npx vitest run src/lib/url.test.ts     # one unit test file
cd frontend && npm run e2e -- -g "name"               # Playwright against the running stack (skips without .env.local credentials)
```

## Specs and workflow

- Feature work is driven by a spec in `spec/<name>/spec.md` at the repository root, or in `backend/spec/` and `frontend/spec/` for a spec that belongs to one side (read it with `Architecture.md` and the sub-guides before coding). A spec that records a decision ("don't re-ask", "don't fix this back") is binding; where it disagrees with the code, follow its intent and report the difference. Investigations leave a `root-cause.md` next to the spec.
- Existing specs: `spec/weekly-ask-ai-limit` (Ask AI questions are a weekly allowance: lazy reset on `user_usage.window_start`, window-aware refunds and ledger, `resetsAt` in `GET /usage` and the 403, the "Out of credits" message), `spec/ask-ai-providers` (the provider chain: config-driven providers, safety rules, cool-downs), `spec/graph-controls-and-auth-fixes` (test-code filter, draggable graph nodes, OAuth redirect URIs, generic `202` on duplicate registration) and `spec/oauth-login-hydration-and-account-linking` (one session-boot path with a timeout and retry screen, account linking from Settings with a browser-bound link intent). `spec/account-deletion` (delete an account without resetting usage: the usage ledger, restore on registration and linking, no refunds on purge; amended: the links of the repositories that held a credit are kept too and come back as `NOT_ANALYZED` entries that the owner can analyse with the credit already spent or delete for a refund; deletion overwrites the ledger, the maximum rule is for restore and merge only), `spec/graph-default-and-name-filter`, `spec/ask-ai-overview-and-guidance` and `spec/razorpay-premium` (Premium through Razorpay: one-time or auto-renew, the signed webhook as the source of truth with `BillingService.applyPayment` as the one place that extends Premium, reminder and expiry emails, and after a 3-day grace period only the 5 most recently used repositories are kept). Also `backend/spec/ask-ai-backend` (Ask AI: indexing, retrieval, chat, conversations; built) and `frontend/spec/ask-ai-frontend` (its UI; built after the backend, against the real API). Also `frontend/spec/ui/spec-dark-ui.md` (the dark theme; its hard rule is that the light theme stays pixel-identical, guarded by a token snapshot and zero-tolerance screenshots). Their durable rules are summarised in the sub-guides; do not regress them.
- Cross-cutting rules that are easy to miss: no mock data anywhere (backend tests use real fixtures and Testcontainers, frontend tests use small inline inputs, Playwright interception only injects network faults); no test-only endpoints in production code; Hallmark "Coral" design tokens only in the frontend, in two colourways (Light is the default and must stay pixel-identical; Dark is an override block in `frontend/src/styles/tokens.css`, the only file with colours; components never branch on the theme; see `frontend/CLAUDE.md` → Design).
- Commit messages carry no `Co-Authored-By` or other Claude/Anthropic trailer.

## Running the whole stack

1. PostgreSQL and Neo4j running (Docker), `backend/.env` filled in (`backend/README.md`), then `./mvnw spring-boot:run` from `backend/` (port 8080).
2. `npm run dev` from `frontend/` (port 5173). The Vite proxy forwards `/api`, `/oauth2` and `/login/oauth2` to the backend, so the browser sees one origin.
3. Google/GitHub sign-in through the frontend builds redirect URIs on the frontend origin (`http://localhost:5173/login/oauth2/code/{google,github}`), so those must be registered with the providers and you must browse via `http://localhost:5173` (not `127.0.0.1`). Google can hold several redirect URIs (keep the `:8080` one next to it if you want); a GitHub OAuth App holds **one** callback URL, so it serves either the proxy flow or direct `:8080` use (a second OAuth App with its own `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` covers both). A `redirect_uri_mismatch` is a provider-console setting; see `backend/README.md`.

## System architecture

```
Frontend (React) ──REST──▶ Spring Boot API
                              ├── Repository service ──▶ GitHub / git
                              ├── Analysis service ──▶ Code Intelligence Model ──▶ Neo4j (graph) + pgvector (AI context)
                              ├── User / Account service ──▶ PostgreSQL
                              └── AI service (V1+) ──▶ Neo4j + pgvector ──▶ context builder ──▶ LLM
```

The core analysis pipeline works without any AI, and V0.1 must be useful on its own:
repository → ingestion (clone, file discovery, language detection) → Tree-sitter parsing → AST and symbol/reference analysis → **Archyra Intermediate Representation (IR)** → graph builder → Neo4j → REST API → React Flow.

The AI layer (V1+) consumes the graph; it does not replace it. The graph says what exists and how it connects, and the AI explains what those relationships mean.

## Database boundaries

Each store has one job. Do not duplicate data across them except for IDs.

| Store | Owns | Notes |
| --- | --- | --- |
| PostgreSQL (Spring Data JPA) | Analysis metadata (`analyses`, `analysis_metadata`), accounts and sessions (`users`, `linked_identities`, `refresh_tokens`, `one_time_tokens`), and later usage limits, subscriptions and conversations | Relational and transactional data. Schema comes from Hibernate `ddl-auto=update` today; add migrations before production |
| Neo4j | The code graph | Every graph node is scoped to an `analysisId` |
| PostgreSQL + pgvector | Embeddings of code and graph context for RAG (V1+) | Same Postgres instance |

The Neo4j model is meant to be stable ("the foundation you do not want to rebuild later"). Extend it rather than reshaping it:
- Nodes: `Repository`, `Module`, `File`, `Class`, `Interface`, `Method`, `Function`
- Relationships: `IMPORTS`, `CONTAINS`, `CALLS`, `DEPENDS_ON`, `EXTENDS`, `IMPLEMENTS`, `REFERENCES`


## API standards

- Every route lives under `/api/v1`. Analysis data is nested under `/api/v1/analyses/{analysisId}/...` (`repository`, `files`, `classes`, `methods`, `nodes`, `graph`, `architecture`, `chat`, `explain`). The full target tree is at the end of `Architecture.md`.
- **Analysis is asynchronous.** `POST /api/v1/analyses` with `{ "repositoryUrl" }` returns `{ analysisId, status: "QUEUED" }` immediately. Clients poll `GET /api/v1/analyses/{analysisId}` for `status` and `progress`. The statuses are `QUEUED`, `CLONING`, `PARSING`, `ANALYZING`, `BUILDING_GRAPH`, `COMPLETED` and `FAILED`, plus `NOT_ANALYZED` for a repository restored to a returning user (idle: no graph, credit already spent; `POST /analyses/{id}/analyze` runs it without charging).
- **Extend endpoints instead of adding new ones.** `GET /graph` is the single graph endpoint for every version. It grows query parameters over time: `nodeType` and `relationshipType` in V0.1; `rootNodeId`, `depth`, `nodeTypes`, `relationshipTypes`, `direction` and `maxNodes` in V2; `level` in V3. The same rule applies to `/chat`, which gains `expertiseLevel` in V3.
- Graph responses always use the React Flow shape: `{ nodes: [{ id, type, name, file, ... }], edges: [{ source, target, type }] }`. Expand-style endpoints add a `centerNode`.
- List endpoints paginate with `page` and `size`. Filters are optional query parameters.
- Errors return a machine-readable `code` plus a `message`. For example, a gated feature returns `403 { "code": "PREMIUM_FEATURE", "message": ... }`.
- **Tier gating happens on the server** (Spring Security plus a subscription service), never only in the frontend. The plan is read from the database on every check, never from the access token.
- **Auth, ownership, premium and rate-limit rules, and their error codes:** an analysis is visible only to the user who started it (anything else is a 404), `/api/v1/**` needs a bearer token except the public `/auth/**` routes, and a refused request returns `{ code, message }` (for example `AUTHENTICATION_REQUIRED`, `PREMIUM_FEATURE`, `USAGE_LIMIT_EXCEEDED`, `RATE_LIMITED`). Free users have a lifetime allowance of analyses and a weekly allowance of Ask AI questions (15 per week, resetting every Monday 00:00 in `USAGE_RESET_ZONE`) (`GET /api/v1/usage`); a returning user who deleted an account gets their used counts and their repositories back as `NOT_ANALYZED` entries (`POST /analyses/{id}/analyze` uses the saved credit); see `Architecture.md` → Usage. Details: `backend/spec/security/design.md`.
- AI responses include `sources` (the graph nodes they used). Architecture detection includes a `confidence` score and must not be presented as certain.
- The graph and the AI share one expertise model: `BEGINNER`, `INTERMEDIATE` and `EXPERT`.

## Roadmap

- **V0.1 Codebase Understanding** (done): accept a GitHub URL, parse it, build the graph and the V0.1 endpoints (`analyses`, `repository`, `graph`, `nodes`, `files`, `classes`), and show basic technology/framework information. No AI. The backend endpoints are all implemented; detected technologies are returned as `technologies` by `GET /repository` and shown on the Overview.
- **V0.5 Product Foundation**: authentication and authorization are implemented (see `backend/spec/security/spec.md` and the Security section of `backend/CLAUDE.md`): email/password, Google and GitHub login, short-lived access JWT plus rotating refresh cookie, email verification and password reset, per-user ownership of analyses, server-side premium gating, rate limiting, `/users/me`, and the admin user API (`/admin/users`). Analysis history is done (`GET`/`DELETE /analyses`, and `POST /analyses/{id}/reanalyze`, which replaces the old analysis when the re-run completes). Signed-in users can link and unlink Google/GitHub from Settings (`/users/me/linked-identities`; see `Architecture.md` → Linked sign-in methods). `GET /admin/stats`, usage tracking and the free-tier analysis limit (`GET /usage`, 403 `USAGE_LIMIT_EXCEEDED`; deleting an analysis returns its credit to a free user) are implemented.
- **V1 AI Codebase Explorer**: Spring AI, "Ask AI" chat with sources, conversations, `/architecture` interpretation, `/explain` for a node, and RAG over the graph plus pgvector. **The Ask AI backend is built** (`POST /analyses/{id}/chat`, `/conversations`, `GET`/`POST /analyses/{id}/ai-index`; a configurable chain of free LLM providers (Gemini first; `spec/ask-ai-providers/spec.md`, `GET /admin/ai/providers`), local embeddings, hybrid retrieval over the graph and pgvector, grounded sources, 15 questions per week (resetting every Monday) for FREE users; a question about the whole project is answered from the README, the manifests and a project fact pack, and one that nothing in the repository relates to is refused with `422 QUESTION_NOT_ANSWERABLE` before it is sent or counted, `spec/ask-ai-overview-and-guidance`): see `Architecture.md` → Ask AI and `backend/CLAUDE.md`. Its UI is built too (`frontend/CLAUDE.md`); `/architecture`, `/explain` and expertise levels are still to do. Deployment happens after V1.
- **V2 Interactive Architecture Graph** (premium): drill down from a class to its methods and then to their calls, using `nodes/{id}/relationships`, `nodes/{id}/expand`, `classes/{id}/methods` and `methods/{id}/dependencies`, plus parameters on `/graph`.
- **V3 Adaptive Architecture**: graph views by expertise level. The free tier gets `BEGINNER` and limited `INTERMEDIATE`; `EXPERT` is premium only.

Deferred until there is real load: Redis and RabbitMQ for background processing. Payments are built through Razorpay (`spec/razorpay-premium`, `Architecture.md` → Billing).
