# Spec: Graph defaults (modules first) and a name filter

Location: `spec/graph-default-and-name-filter/spec.md` (repository root). **Frontend only. No backend change.**

Read first: `CLAUDE.md`, `frontend/CLAUDE.md` (every rule applies: hooks only, one query-key file, Hallmark Coral tokens only, `Blueprint` banned, no mocks, accessibility, `npm run verify` green), `Architecture.md` (graph semantics, `DEPENDS_ON` between Module nodes), `spec/graph-controls-and-auth-fixes/spec.md` (scope control, dragging: do not regress). Existing code: `components/graph/adapter.ts` (`defaultFilter`, `visibleGraph`), `features/repository/architecture/{ArchitecturePage,GraphFilters,graphParams}.ts*`, `features/repository/overview/OverviewPage.tsx`, `components/graph/{scope,useLayout,GraphCanvas}`.

## 1. Task A: the initial graph is Modules joined by DEPENDS_ON

### Decisions
- "Initially" means: the Architecture tab and the Overview preview when the URL has no `nodes` and no `edges` parameter. Explicit parameters and anything the user changes behave exactly as today.
- Default view = node type `MODULE`, edge type `DEPENDS_ON` only (no `EXTENDS`/`IMPLEMENTS`).
- **Fallback, so the first screen is never empty or useless.** Use the module view only when, **after the tab's default scope is applied** (`working` hides test modules), there are at least 2 modules and at least 1 `DEPENDS_ON` edge between two of them. Otherwise fall back to what `defaultFilter` returns today (classes and interfaces, then files and functions). A repository with a single package therefore still opens on its classes.
- `defaultFilter` stays the single source of truth for both tabs. It must now take the scope into account (add a parameter; update both callers and the tests).
- The type chips show `Modules` selected and the edge chips show `DEPENDS_ON` only. When the module default is active, show a short caption near the filters ("Modules joined by DEPENDS_ON. Turn on Classes to look inside.") that disappears as soon as the user changes the selection. No storage, no persisted preference.
- Module cards keep today's label rule (`displayName`: last package segment, full name in tooltip and inspector).

### Tests
- Unit (`adapter.test.ts`): module view chosen for a normal graph; fallback for one module, for modules without edges, for modules that are all test code under `working`, and for languages without classes; the existing expectations for the fallbacks still hold.
- E2E (`scripts/e2e/graph-controls.spec.ts`, reuse the COMPLETED analysis of the test account): a fresh Architecture URL shows only module nodes and only `DEPENDS_ON` edges (skip with a clear message if the analysis has fewer than 2 modules with an edge), the Overview preview shows the same set, and choosing Classes switches to the class view.

## 2. Task B: a name filter on the graph

### Behaviour
- A search field in the graph toolbar (also visible in fullscreen): label "Filter by name", `type="search"`, a clear button, placeholder "e.g. service".
- **Semantics.** Case-insensitive substring match on the node's `name`. It applies **on top of** the selected node types and the scope: with Classes and Interfaces selected and the term `service`, only classes and interfaces whose name contains `service` are shown, and nothing else. Edges are shown only when both ends are shown (the existing rule). It does not pull in neighbours.
- The term is trimmed, at most 100 characters, one term (no operators). Empty means no filter.
- **URL:** `?q=<term>`, left out when empty (default URLs stay clean), restored on load, updated with `replace` (typing never fills the history). Debounce the URL and layout update by about 250 ms; the input itself updates instantly.
- **Selected node:** if the selected node (`?node=`) is hidden by the filter it stays selected, and the inspector shows a short "Hidden by the filter" notice with a *Clear filter* action (same pattern as the scope notice on Relationships).
- **Counts and states:** a polite live region announces "Showing 12 of 87" (matching nodes of the selected types). When nothing matches show the empty state "No <selected types> match “service”" with *Clear filter* and *Show all* actions. The type chips keep their own counts.
- **Layout and drag:** the filtered set re-lays out through the existing cached layout; manually moved nodes keep their positions (`overlayPositions`), and hidden nodes keep theirs for when they return. Reset layout, minimap, legend, fullscreen follow the visible set.
- Keyboard: `/` focuses the field when no input is focused (only on the Architecture tab); `Escape` in the field clears it. Visible focus, labelled, no colour-only signals.

### Implementation notes
- Extend the pure `visibleGraph` filter object with an optional `query` and apply it **before the edge check**, next to the scope. Keep it a pure function so it is unit-testable; the layout cache key must include the visible ids (it already does).
- `graphParams.ts` parses and serialises `q`; unknown or over-long values fall back to empty. The component never calls fetch; this needs no new endpoint or hook beyond existing ones.
- Update the Relationships tab only if its class browser already has a search field: keep its behaviour consistent (same case-insensitive substring rule). Do not add a new control there.

### Tests
- Unit: query alone; query with node types; query with `tests`/`working`/`all` scope; case-insensitivity; trimming; edges removed when an end is hidden; no match; modules matched by their full name; the URL parser and serializer.
- E2E: typing `service` in the Architecture tab (class view) reduces the nodes to matching names and updates `?q=`; reload restores it; Escape and the clear button remove it; with the module default a term filters the modules; a filtered-out selected node shows the notice.

## 3. Quality bar and acceptance criteria
- Tokens only (`npm run check:tokens`), the Hallmark sloplint as in `frontend/CLAUDE.md`, no `any`, accessibility as above, no mocks.
- No regression of the scope control, dragging, Reset layout, minimap, fullscreen, deep links (`?node=`, `?view=`), or the Files and Relationships tabs.

Acceptance:
1. A new Architecture URL and the Overview preview open on modules joined by `DEPENDS_ON` when the rule in section 1 holds, and on today's default otherwise.
2. The type and edge chips match what is drawn; the caption disappears when the selection changes.
3. The name filter shows only nodes of the selected types whose name contains the term, with edges only between shown nodes; empty, selected-node-hidden and fullscreen cases behave as described.
4. `?q=` round-trips; typing does not grow browser history.
5. Dragged positions survive filtering; `npm run verify` and the new e2e tests pass.
6. `frontend/CLAUDE.md` (Graph, URL params) and `Architecture.md` (frontend section) describe the new default and `q`.

## 4. Workflow
Commit per task (Task A, Task B). **Commit messages must not contain `Co-Authored-By`, a co-author of any kind, or any "Generated with" / Claude / Anthropic line.**