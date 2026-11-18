# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Archyra ("the architecture behind your code") analyzes a GitHub repository, builds a code graph (files, classes, methods, imports, calls, inheritance) and renders it as an explorable architecture diagram. AI explanations come later, on top of that graph.

`Architecture.md` is the design source of truth for the roadmap, API contracts and data model. Read the relevant version section before implementing a feature, and update it when a design decision changes.

## Repository layout

- `backend/`: Spring Boot API and analysis engine. Build commands, package conventions and backend rules are in `backend/CLAUDE.md`.
- `frontend/`: the React app, built and talking to the real `/api/v1` backend through the Vite proxy (no mock data). Commands, structure and UI rules are in `frontend/CLAUDE.md`.

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
- **Analysis is asynchronous.** `POST /api/v1/analyses` with `{ "repositoryUrl" }` returns `{ analysisId, status: "QUEUED" }` immediately. Clients poll `GET /api/v1/analyses/{analysisId}` for `status` and `progress`. The statuses are `QUEUED`, `CLONING`, `PARSING`, `ANALYZING`, `BUILDING_GRAPH`, `COMPLETED` and `FAILED`.
- **Extend endpoints instead of adding new ones.** `GET /graph` is the single graph endpoint for every version. It grows query parameters over time: `nodeType` and `relationshipType` in V0.1; `rootNodeId`, `depth`, `nodeTypes`, `relationshipTypes`, `direction` and `maxNodes` in V2; `level` in V3. The same rule applies to `/chat`, which gains `expertiseLevel` in V3.
- Graph responses always use the React Flow shape: `{ nodes: [{ id, type, name, file, ... }], edges: [{ source, target, type }] }`. Expand-style endpoints add a `centerNode`.
- List endpoints paginate with `page` and `size`. Filters are optional query parameters.
- Errors return a machine-readable `code` plus a `message`. For example, a gated feature returns `403 { "code": "PREMIUM_FEATURE", "message": ... }`.
- **Tier gating happens on the server** (Spring Security plus a subscription service), never only in the frontend. The plan is read from the database on every check, never from the access token.
- **Auth, ownership, premium and rate-limit rules, and their error codes:** an analysis is visible only to the user who started it (anything else is a 404), `/api/v1/**` needs a bearer token except the public `/auth/**` routes, and a refused request returns `{ code, message }` (for example `AUTHENTICATION_REQUIRED`, `PREMIUM_FEATURE`, `USAGE_LIMIT_EXCEEDED`, `RATE_LIMITED`). Free users have a lifetime allowance of analyses (`GET /api/v1/usage`); see `Architecture.md` → Usage. Details: `backend/specs/security/design.md`.
- AI responses include `sources` (the graph nodes they used). Architecture detection includes a `confidence` score and must not be presented as certain.
- The graph and the AI share one expertise model: `BEGINNER`, `INTERMEDIATE` and `EXPERT`.

## Roadmap

- **V0.1 Codebase Understanding** (done): accept a GitHub URL, parse it, build the graph and the V0.1 endpoints (`analyses`, `repository`, `graph`, `nodes`, `files`, `classes`), and show basic technology/framework information. No AI. The backend endpoints are all implemented; technology/framework information is stored in `analysis_metadata` but not exposed by an endpoint yet.
- **V0.5 Product Foundation**: authentication and authorization are implemented (see `backend/specs/security/spec.md` and the Security section of `backend/CLAUDE.md`): email/password, Google and GitHub login, short-lived access JWT plus rotating refresh cookie, email verification and password reset, per-user ownership of analyses, server-side premium gating, rate limiting, `/users/me`, and the admin user API (`/admin/users`). Analysis history is done (`GET`/`DELETE /analyses`, and `POST /analyses/{id}/reanalyze`, which replaces the old analysis when the re-run completes). `GET /admin/stats`, usage tracking and the free-tier analysis limit (`GET /usage`, 403 `USAGE_LIMIT_EXCEEDED`; deleting an analysis returns its credit to a free user) are implemented.
- **V1 AI Codebase Explorer**: Spring AI, "Ask AI" chat with sources, conversations, `/architecture` interpretation, `/explain` for a node, and RAG over the graph plus pgvector. The LLM provider is undecided; the plan is to compare GPT, Gemini and Claude. Deployment happens after V1.
- **V2 Interactive Architecture Graph** (premium): drill down from a class to its methods and then to their calls, using `nodes/{id}/relationships`, `nodes/{id}/expand`, `classes/{id}/methods` and `methods/{id}/dependencies`, plus parameters on `/graph`.
- **V3 Adaptive Architecture**: graph views by expertise level. The free tier gets `BEGINNER` and limited `INTERMEDIATE`; `EXPERT` is premium only.

Deferred until there is real load: Redis and RabbitMQ for background processing. Payments are planned through Razorpay.
