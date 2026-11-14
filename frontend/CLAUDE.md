# frontend/CLAUDE.md

Frontend-specific guidance. System-wide architecture, API standards and the roadmap are in the root `CLAUDE.md` and `Architecture.md`.

## Status: deferred

Frontend development is **deferred until the backend V0.1 APIs are ready**: `POST /api/v1/analyses`, `GET /api/v1/analyses/{id}`, `/repository`, `/graph`, `/nodes/{nodeId}`, `/files` and `/classes/{classId}`. Do not scaffold the app or invent API responses ahead of the backend. Build against the real contracts in `Architecture.md`, and against the backend once it serves them.

## Planned stack

- React with TypeScript, built with Vite
- Tailwind CSS for styling
- React Router for routing
- TanStack Query for all server state (fetching, caching, polling)
- React Flow for graph rendering. The V2 roadmap adds custom graph UI on top of it.

## UI rules

**Analysis lifecycle and polling**
- Submitting a repository calls `POST /api/v1/analyses`, which returns `{ analysisId, status: "QUEUED" }` immediately. The page then polls `GET /api/v1/analyses/{analysisId}`.
- Poll with TanStack Query's `refetchInterval`, and stop polling once `status` is `COMPLETED` or `FAILED`. Show `status` and `progress` while polling (`QUEUED` → `CLONING` → `PARSING` → `ANALYZING` → `BUILDING_GRAPH`).
- Request results (`/graph`, `/repository`, `/files` and so on) only after `COMPLETED`. Show the failure reason when the status is `FAILED`.

**Graph rendering**
- `GET /api/v1/analyses/{id}/graph` returns `{ nodes: [{ id, type, name, file }], edges: [{ source, target, type }] }`. Map this directly to React Flow nodes and edges in one adapter. Components must not reshape API data ad hoc.
- The graph endpoint grows query parameters by version rather than gaining new endpoints: `nodeType` and `relationshipType` in V0.1, `rootNodeId`, `depth`, `nodeTypes`, `relationshipTypes`, `direction` and `maxNodes` in V2, and `level` in V3. Keep the graph query keyed on all its parameters so the new ones slot in.
- Color carries meaning (V2): class, interface, external dependency, database, API and method nodes each get their own color, as do inheritance, dependency and method-call edges. Define these colors once, keyed by node and edge `type`.
- Planned V2 interactions: click a node to expand it, double-click to enter it, hover for relationship information, search, filter by relationship type, hide unrelated nodes, go back through the exploration history, zoom, and highlight dependency paths.

**AI answers (V1+)**
- `POST /api/v1/analyses/{id}/chat` returns `{ answer, sources: [{ type, id, name }] }`. Always show the sources as **clickable citations** that navigate to or highlight that node in the graph, or open its detail view (`/nodes/{nodeId}`). Never show an answer without its sources.
- Architecture interpretations (`/architecture`) come with a `confidence` score. Show it, and never present the detected architecture as certain.

**Tiers and errors**
- The backend enforces premium features. The UI may hide or mark gated features, but it must also handle `403 { "code": "PREMIUM_FEATURE", "message" }` gracefully, for example with an upgrade prompt.
- Handle errors by the `code` field, not by matching message text.

## Planned structure

The repository view follows `Architecture.md`: Dashboard → Repository → Overview / Architecture / Files / Relationships / Ask AI.
