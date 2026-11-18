# Spec: Graph test filter, draggable nodes, OAuth redirect fix, duplicate-registration handling

> **For Claude Code.** Save location: `specs/graph-controls-and-auth-fixes/spec.md` at the repository root. This spans `backend/`, `frontend/` and two external provider consoles, so plan the backend flag before the frontend filter.
>
> **Read first:** this file, `Architecture.md`, the root `CLAUDE.md`, `backend/CLAUDE.md`, `frontend/CLAUDE.md`, `backend/README.md`, `backend/specs/security/design.md` and `spec.md`. **Follow the existing conventions in those files** (package-by-feature, records for DTOs, constructor injection, strict-stubs Mockito, hooks-only components, design tokens, no mocks). Where this spec and the code disagree on *behavior*, follow this spec and say so in your report. Where a name below differs from the code, follow the code.
>
> **Order of work:** §11. Keep `./mvnw clean test` (backend) and `npm run verify` (frontend) green after each phase.

---

## 0. The four items at a glance

| # | Item | Decision summary |
|---|---|---|
| 1 | **Graph test-class filter** | Backend adds a `test` boolean, **computed at read time from the file path**, to graph nodes, node detail and files. The UI gets a **Working / Tests / All** control. Graph and Relationships default to **Working**, Files to **All**. |
| 2 | **OAuth `redirect_uri_mismatch`** | Local dev goes through the Vite proxy, so providers must have `http://localhost:5173/login/oauth2/code/{google,github}` registered. The GitHub OAuth App holds **one** callback URL. Verify from the real redirect, fix any code/config issue, list the exact console changes. |
| 3 | **Duplicate email registration** | **Keep the generic `202`** (no account-enumeration leak). Do **not** return 409 and do not show "Email already registered" on the page. Improve the UX instead and **email the existing owner**. |
| 4 | **Draggable nodes** | Every node is draggable, with multi-select group drag, keyboard nudging and touch drag. Positions are kept **for the session** with a **Reset layout** button. Nothing is persisted. |

**No mock data anywhere.** This repo has a strict no-mock policy (`frontend/CLAUDE.md`). Backend tests use real fixtures under `src/test/resources`. Frontend tests use small inline inputs only. There is nothing to add under any mock directory.

---

## 1. Item 1: Test-class filter

### 1.1 Decisions (already made, don't re-ask)
- **Where it's decided:** backend, **at read time, from the stored `file` path**. Works on every existing analysis without re-analysis. No new graph query parameter, so the `GraphPremiumGate` is unaffected and the filter is available to **every plan** (no PRO badge).
- **What counts as a test:** **path and filename conventions only** (§1.2). No annotation or framework detection.
- **Which nodes:** every code node **declared in a test file**: `Class`, `Interface`, `Method`, `Function` and `File`. A `Module` is a test module **only if it has at least one file and every file in it is a test file**. The `Repository` node is never a test.
- **Three states:** `Working` (hide tests), `Tests` (**strict**: only test nodes and the edges among them, nothing dimmed), `All`.
- **Defaults:** Architecture graph **Working**. Relationships class browser **Working**. Files table **All**. Each tab keeps its **own** setting in its own URL.

### 1.2 Backend: the classifier
Put one pure, stateless class in **`common/`** (it is needed by both `graph/` and `codebase/`, so it follows the "shared code goes to `common/`" rule), e.g. `common/TestPathClassifier` with `boolean isTestPath(String repoRelativePath)`. Input is the repo-relative path with `/` separators exactly as stored in the graph's `file` property.

**Rules, in this order (first match wins):**
1. **Main source sets are never tests.** If the path has the segment pair `src/main/` (Maven/Gradle), it is **not** a test. This protects packages that happen to be called `test` inside real main code (e.g. a `…/main/java/…/test/…` support package).
2. **Test source sets are tests.** The segment pair `src/test/`, or `src/<name>Test/` or `src/<name>Tests/` (for example `src/integrationTest/`, `src/functionalTest/`), or `src/testFixtures/`.
3. **Test directory segments at any depth** (exact, lower-case): `test`, `tests`, `__tests__`, `__test__`, `spec` (Ruby and JS convention). A segment like `testing`, `specs` or `latest` does **not** match.
4. **C# test projects:** a directory segment ending in `.Tests`, `.Test`, `.UnitTests` or `.IntegrationTests`.
5. **File-name conventions** (match the file name, case-sensitive):
    - Java, Kotlin: `*Test.java|kt`, `*Tests.java|kt`, `*IT.java|kt`, `Test*.java|kt`
    - Python: `test_*.py`, `*_test.py`, `conftest.py`
    - Go: `*_test.go`
    - JavaScript/TypeScript/Vue/Svelte: `*.test.{js,jsx,ts,tsx,mjs,cjs,vue,svelte}`, `*.spec.{…same…}`
    - Ruby: `*_spec.rb`, `*_test.rb`
    - C#: `*Test.cs`, `*Tests.cs`
    - PHP: `*Test.php`
    - Solidity: `*.t.sol`, `*.test.sol`
    - Rust: `*_test.rs` (note: Rust unit tests inline with `#[cfg(test)]` are **not** detected, and that is a documented limitation)
6. Otherwise: **not** a test.

Requirements:
- The rule table is the contract. Implement it as data/constants, not a tangle of `if`s, and make it **table-driven in tests** (a path → expected boolean list covering each rule, each language, and each negative case such as `src/main/java/com/x/test/Util.java` → false, `src/contest/App.java` → false, `docs/specs/a.md` → false).
- Null or blank path → `false`. Never throws.
- Module classification needs the files of a module. The implementation (one extra Cypher read grouping a module's file paths, or deriving from `CONTAINS` data already read) is yours to choose, but: module is a test iff `files ≥ 1 && all files are test`. Keep Cypher built from fixed strings and bound parameters only (existing Neo4j rules).
- If `Method`/`Function` nodes don't carry a `file` property in the graph, derive their flag from their containing file (via `CONTAINS`/`parentId`). **Check the actual Neo4j data and the graph mapper before assuming.**

### 1.3 Backend: API contract changes (additive, backward compatible)
Add a boolean field named exactly **`test`**, **always present** (never null or omitted), to:
- every node in `GET /analyses/{id}/graph` (`nodes[]`),
- `GET /analyses/{id}/nodes/{nodeId}`,
- every item of `GET /analyses/{id}/files` and the top level of `GET /analyses/{id}/files/{fileId}`.

(`/classes/{id}` and the nested lists of the file detail do **not** need it.)

**New optional query parameter on `GET /files`: `test`** (`true` = only test files, `false` = only non-test files, absent = both).
- Any other value → `400 VALIDATION_ERROR` listing the allowed values (same style as `language`).
- It combines with `language` and `path` with AND.
- Because the list is paginated, the filter must be applied **before** pagination: `totalElements` and `totalPages` count the files that match **all** filters. When `test` is absent, keep today's pagination path unchanged. When present, fetch the matching rows (id, name, path, language only, which are bounded by the ingestion file-count limits), classify in Java, then page in memory.
- `/files` has no premium gate today and this doesn't add one.

**No new `/graph` parameter.** The frontend filters client-side on `test`. The query key still carries the existing parameters.

Rules for later work (add to `backend/CLAUDE.md`): any endpoint that returns graph or file nodes must populate `test` through the shared classifier.

### 1.4 Backend: tests
- `TestPathClassifierTest`: the table described above (pure unit test).
- Graph: controller/service tests that every node in `/graph` and `/nodes/{id}` has `test`, including `Module` (mixed module → false, all-test module → true) and `File`. At least one **Testcontainers integration test** against a fixture repository that contains both `src/main` and `src/test` code (add a fixture, e.g. a small Java repo with a service, a repository and a test, and a function-oriented fixture such as Python with `tests/test_x.py`), asserting flags on a real graph.
- Files: `test=true|false|absent`, invalid value → 400, combination with `language`/`path`, and **pagination counts** (`totalElements`/`totalPages` correct after the test filter, page 0 and page 1).
- `EveryEndpointIsProtectedTest` still passes (no new endpoint, only a new parameter).
- Run `./mvnw clean test` (not an incremental build, per `backend/CLAUDE.md`).

### 1.5 Frontend: scope control and state
- **Control:** a three-way segmented control **`Working | Tests | All`** in the Architecture graph toolbar, next to the node-type and relationship-type filters. It is a proper radio group (keyboard arrows, roving tabindex, `aria-label` such as "Show: working code, tests, or all"), styled with Hallmark tokens only (no raw colors, `npm run check:tokens` must pass). Add a short help tooltip: what counts as a test (path/filename conventions) and the Rust limitation if there is space.
- **URL state:** a new param `scope` with values `working | tests | all`, alongside the existing `?nodes=&edges=&node=&view=`. **Omit the param when it equals that tab's default** (Architecture: `working`; Relationships: `working`; Files: `all`), so default URLs stay clean and a non-default value is always explicit. Unknown values fall back to the default. Each tab owns its own `scope`.
- **Counts:** each option shows how many nodes it would reveal under the current node-type filter (computed client-side from the already-fetched graph), so users can see why a view is empty.
- **Graph adapter (`components/graph/adapter.ts`):** apply the scope **before** the edge visibility check. `working` keeps nodes with `test === false`; `tests` keeps only `test === true` (**strict**: edges survive only when both ends are test nodes); `all` keeps everything. The edge rule is unchanged: an edge shows only if both its ends are visible.
- **Layout:** the layered layout cache key already follows the visible id set; confirm that a scope change re-lays out and that unchanged visible sets still hit the cache.
- **Empty states:** `Tests` with no test nodes → "No test code found in this repository" with a *Show all* action. `Working` with nothing visible (a repository that is entirely tests) → explain and offer *Show all*. Never leave a blank canvas without a reason.
- **Inspector:** show a **Test** badge when `test` is true. The accessible node-list view and the minimap follow the same visible set.
- **Function-oriented repositories** (no classes) have their default module/function view filtered by the same flag. Nothing special beyond the module rule in §1.1.
- **Types and probes:** add `test: boolean` to `GraphNode`, `NodeDetail`, `FileItem` and `FileDetail` in `src/api/types.ts`, and extend `scripts/e2e/probe-api.mjs` to check the field. Verify against the **running** backend.

### 1.6 Frontend: Relationships tab
- Its own `scope` (default `working`), same control component.
- The **browse list** (classes and interfaces from the fetched graph) follows the scope.
- The **detail panel** (extends / implements / depends on / depended on by / methods) is **not** filtered by scope, because it describes real relationships (hiding "depended on by FooTest" would hide test coverage). Test items inside it carry the **Test** badge.
- A deep-linked selection that the scope would hide stays selected and shows a small notice with a *Show all* action. Don't silently drop it.

### 1.7 Frontend: Files tab
- Its own `scope` (default **`all`**) in the filter bar next to the language and path filters. `working` sends `test=false`, `tests` sends `test=true`, `all` sends nothing.
- The files query key carries `test`. Changing scope resets to page 0. The "Showing 1–50 of N" text uses the server's `totalElements`.
- A **Test** badge on test rows and in the detail drawer.

### 1.8 Frontend: tests
- Unit tests: the adapter's scope behavior (working, strict tests, all; edges across the boundary are dropped; module and function repos), the scope-from-URL parsing with per-tab defaults, and the count calculation.
- E2E (Playwright, real backend, skipped without credentials): analyze a repository that has tests, toggle the three states on the graph and see the node count change, check `scope` in the URL and its removal at the default, the Files filter and page reset, and the Relationships list.

---

## 2. Item 2: OAuth `redirect_uri_mismatch`

### 2.1 What is known
- Symptom: the provider (Google or GitHub) shows `redirect_uri_mismatch`.
- Login must work **locally through the Vite proxy** (`http://localhost:5173`). Direct `:8080` use is not required.
- GitHub is **one OAuth App** for everything. As far as I know a GitHub OAuth App accepts **only one** Authorization callback URL, so it can't hold both the `:8080` URL from `backend/README.md` and the `:5173` one. This is the most likely cause for GitHub. Google allows several authorized redirect URIs.
- Spring's default redirect URI is `{baseUrl}/login/oauth2/code/{registrationId}`. The registrations are `google` and `github`. Behind the proxy, with `changeOrigin: false`, `xfwd` and `server.forward-headers-strategy=native`, the base URL should resolve to `http://localhost:5173`.

### 2.2 What Claude Code must do
1. **Derive the real value, don't assume it.** Read `application.properties`, the OAuth2 client registration properties, `auth/oauth2/*`, `SecurityConfig`, `vite.config.ts` and `backend/README.md`. Look for anything that would override the default: an explicit `redirect-uri`, a hard-coded base URL, `frontend-base-url`, forward-header settings, a custom `ClientRegistrationRepository`.
2. **Prove it from the actual redirect.** With the backend and `npm run dev` running, request the authorization start URLs **without following redirects** and read the `Location` header:
    - through the proxy: `/oauth2/authorization/google` and `/oauth2/authorization/github` on `http://localhost:5173`,
    - directly on `http://localhost:8080` for comparison.
      Parse the `redirect_uri` query parameter from each `Location`. **The values you print in your report must come from these responses.**
3. **Fix real defects.** Expected value through the proxy: `http://localhost:5173/login/oauth2/code/google` and `…/github`. If the observed `redirect_uri` differs (wrong port, `127.0.0.1` vs `localhost`, `https` vs `http`, missing forwarded host/port), fix the cause. Prefer the **proxy configuration** (`xfwd`, `changeOrigin: false`, forwarded host/port/proto) and then backend config, and do **not** hard-code a redirect URI in properties unless there is no other way (if you must, make it environment-driven and document it). Backend changes are allowed for this item.
4. **Regression tests.**
    - Backend: a `RANDOM_PORT` real-HTTP test (the same pattern as `NodeEndpointHttpTest`, because forwarded-header handling happens in Tomcat, which MockMvc skips) that calls `/oauth2/authorization/google` and `/github` with the `Host`/`X-Forwarded-*` headers the proxy sends and asserts the `redirect_uri` in the `Location` header. Use the existing WireMock fakes/test profile.
    - Frontend e2e (Playwright, skipped without a running stack): request both authorization URLs through the dev server with redirects disabled and assert the `redirect_uri` is on `localhost:5173`.
5. **Fix the docs that mislead.** The root `CLAUDE.md` says to register the `:5173` URIs "as well as the `:8080` ones". For GitHub that is impossible on one OAuth App. Update the root `CLAUDE.md`, `backend/README.md`, `frontend/CLAUDE.md` and `Architecture.md` (V0.5 frontend note): Google can hold several URIs, a GitHub OAuth App holds one (use a second app and separate `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` in `backend/.env` if both origins are ever needed), and always browse via `http://localhost:5173` (not `127.0.0.1`).

### 2.3 Manual configuration (I do this; Claude Code only reports the exact values it verified)
Expected, to be confirmed by §2.2:

| Provider | Where | Value |
|---|---|---|
| **Google** | Google Cloud Console → APIs & Services → Credentials → your OAuth 2.0 *Web application* client → **Authorized redirect URIs** → add | `http://localhost:5173/login/oauth2/code/google` (keep the existing `:8080` one if present) |
| **GitHub** | GitHub → Settings → Developer settings → OAuth Apps → your app → **Authorization callback URL** (a single field, so replace it) | `http://localhost:5173/login/oauth2/code/github` |

Notes for the report to repeat: Google changes can take a few minutes to apply. "Authorized JavaScript origins" isn't needed for this server-side flow. If the Google consent screen is in *Testing* mode, the signing-in account must be a listed test user. Replacing the GitHub callback URL stops direct `:8080` sign-in with that app, which is fine because only the proxy flow is required.

---

## 3. Item 3: Duplicate email registration

### 3.1 Decision (don't "fix" this back)
`POST /api/v1/auth/register` **keeps answering the same `202` and the same body** whether or not the email already exists. **Do not return `409 EMAIL_ALREADY_REGISTERED`** and **do not show "Email already registered. Try logging in." on the page**, because that would let anyone test which emails have accounts, which the documented security design prevents. The user explicitly chose to keep the generic response and improve the experience instead. Record this in `backend/specs/security/design.md` so it isn't reverted.

### 3.2 Backend behavior for an already-registered email
All of it happens **asynchronously after the response is returned** (the existing bounded `@Async` mail executor), so the response time doesn't reveal whether the account exists. Match the email case-insensitively (emails are stored lower-cased).

| Existing account | What happens |
|---|---|
| **Password account, verified, enabled** | No new user, nothing changed. Send an **"account already exists" notice** to the owner: someone tried to register with this address; if it was you, **log in** (`{frontend-base-url}/login`) or **reset your password** (`{frontend-base-url}/forgot-password`). The links carry **no token**. |
| **Google/GitHub-only account** (no password hash, linked identities) | Same notice, but it names the provider(s): "You signed up with Google/GitHub. Use that button to sign in." plus the forgot-password link (which lets them add a password, as the security design already allows; **verify that in the code**). |
| **Unverified account** | Treat it as a **retry of the signup**: replace the stored **name and password hash with the newly submitted ones** (last registration wins, because nobody has proven ownership yet), invalidate older unused verification tokens, and send a **fresh verification link**, using the existing email-verification path. Do not send the notice. (Why the password is replaced: the owner who follows the new link expects the password they just typed to work, and it removes the pre-registration squatting risk where someone else's password stays attached to your address.) |
| **Disabled account** (`enabled = false`) | Send **nothing** and change nothing. The response is still the same generic `202`. |

- **Throttle:** at most **one** notice per address per hour (a bounded Caffeine cache keyed by the lower-cased address, hashed or not stored in logs). This is in addition to the existing per-IP rate limits and stops the register form being used to spam a victim's inbox. The unverified-retry verification email must respect the existing resend/verification throttling (check what exists and reuse it).
- Implement in the existing `auth/` flow through `UserService`/the register service and the `mail/` `EmailService` (add a method such as `sendAccountExistsNotice`, plain text like the existing mails). Follow the package rules: `auth/` and `mail/` talk through public services, not repositories.
- **Audit:** a structured `security.audit` event for each case (e.g. `REGISTER_EXISTING_ACCOUNT` with the case), no emails or tokens in the log line.
- Email wording must not blame or alarm ("If this wasn't you, you can ignore this message; your account is unchanged.").

### 3.3 Frontend
- No change to API handling: still a `202` and the existing generic confirmation.
- Improve the **check-your-inbox** screen (`/register/check-email`) copy: it must be true for **both** new and existing addresses. Suggested shape: "If this address can be registered, we've sent you an email. **Already have an account?** Log in, or reset your password." with links to `/login` and `/forgot-password`, plus the existing resend action and cooldown.
- The register form keeps a visible "Already have an account? Log in" link (add it if missing).
- **Never** render a sentence that implies the address is or isn't registered.

### 3.4 Tests
- Backend (GreenMail + the existing auth integration test style):
    - the response **status and JSON body are identical** for a new and an existing email (assert equality),
    - existing verified → no second user, exactly one notice email with the login and reset links, and **no** token in it; a second attempt inside the hour sends nothing,
    - OAuth-only existing → notice naming the provider,
    - existing unverified → password and name replaced, old verification tokens invalid, a working fresh link sent, login works with the new password after verifying, and the old password does not,
    - disabled → no email, no change,
    - mixed-case email matches the existing account,
    - `EveryEndpointIsProtectedTest` still passes (the route is already public and listed).
- Frontend: unit test the check-email copy contains the login and forgot-password links and no "already registered" phrasing; e2e where runnable (register the same address twice and see the same screen).
- Update the documented security design and the Architecture.md register description.

---

## 4. Item 4: Draggable graph nodes

### 4.1 Decisions
- **All nodes** in the Architecture graph are draggable (every node type, including methods and functions).
- **Positions are kept for the session** only: held in component state per analysis, **not** persisted to storage or the backend, and **cleared on reload or analysis change**.
- Extras to build: **multi-select + group drag**, **keyboard nudging**, **touch drag**.
- A **Reset layout** button returns to the automatic dagre layout.

### 4.2 Behavior
- **Layout interplay.** Positions come from the layered layout (dagre in `layout.worker.ts`). Keep a `manualPositions` map (node id → position) in state. Whenever the layout is recomputed (scope, node-type or edge filter change, view switch), take the automatic positions and **overlay the manual position for every node that has one and is still visible**. Nodes without a manual position use the new automatic position. The layout cache stays keyed by the visible id set and is unaffected, because the overlay is applied after it. Keep the overlay logic in one small **pure function** and unit-test it.
- **Edges follow nodes** while dragging.
- **Click versus drag.** Set a small drag threshold (a few pixels) so a plain click still selects the node and opens the inspector, and a drag does **not** open or close the inspector or change `?node=`. Selection for the inspector remains driven by the click handler only.
- **Reset layout:** a toolbar button, enabled only when at least one node has been moved, keyboard accessible, with an accessible name. It clears `manualPositions` and re-applies the automatic layout (then fits the view).
- **Multi-select and group drag:** Shift-drag on empty canvas draws a selection box. Ctrl/Cmd-click toggles a node in the selection. Dragging any selected node moves the whole selection. Escape clears the selection. Check which gestures the installed `@xyflow/react` version supports and use its props rather than rebuilding them. Explain the gestures in a short help tooltip on the toolbar.
- **Keyboard nudging:** the existing focusable nodes can be moved with the **arrow keys** (a larger step with Shift) once focused and selected. Check what the installed React Flow does by default and reuse it; if it doesn't cover this, implement it. It moves the whole selection, announces the move politely through the existing live region (throttled, no spam), and must not break Enter-to-select/inspect. The nudge updates `manualPositions` like a drag does.
- **Touch:** one finger on a node drags it. One finger on empty canvas pans as it does now. Two fingers pinch-zoom and pan. The page itself must not scroll while a node is being dragged, and the mobile bottom-sheet inspector must not conflict with node dragging.
- **Minimap and fullscreen** reflect the moved positions. Entering and leaving fullscreen keeps them.
- **Performance:** dragging must stay smooth on the largest analysis available locally (keep `onlyRenderVisibleElements`; update only the changed nodes' state; avoid re-running the layout worker during a drag). Report what you measured.
- **No backend change** for this item.

### 4.3 Frontend tests
- Unit: the overlay function (manual positions override, hidden nodes keep theirs for when they return, new nodes use automatic positions, reset clears).
- E2E (Playwright): drag a node and assert its position changed; toggle a filter and assert the dragged node kept its position while others re-laid out; Reset layout restores the automatic position; clicking (not dragging) still opens the inspector; arrow-key nudge moves a focused node; group drag moves two selected nodes. Skip gracefully without credentials and say so.

---

## 5. Cross-cutting rules
- **Conventions:** backend per `backend/CLAUDE.md` (records for DTOs, thin controllers, no `@Data` on entities, strict stubs, `./mvnw clean test`). Frontend per `frontend/CLAUDE.md` (components use only hooks, `npm run verify`, `check:tokens`, Hallmark tokens only, **no `Blueprint`/grid/dot backgrounds**, never render React Flow's `<Background>`). Run the Hallmark `sloplint` as `frontend/CLAUDE.md` describes for UI changes.
- **Security:** no endpoint becomes public. No secret, token or email in logs. API strings render as text only.
- **API compatibility:** only additive changes (`test` fields, optional `test` parameter on `/files`). Existing clients keep working.
- **Mock data:** none. Don't add any.
- **Don't touch** unrelated behavior (usage limits, premium gate, ownership checks).

---

## 6. Documentation updates (required)
- **`Architecture.md`:** graph, node and file sections (the `test` field and rules summary, the `/files?test=` parameter and its pagination semantics), the register description (generic `202` kept, owner notice, unverified retry, throttle), and the V0.5 OAuth/proxy note (accurate GitHub/Google guidance).
- **`backend/CLAUDE.md`:** `common/TestPathClassifier` and the rule that node/file-returning endpoints must populate `test`; the register behavior; the OAuth note.
- **`frontend/CLAUDE.md`:** the `scope` URL param and per-tab defaults, the adapter's scope step, drag/manual-positions/Reset layout behavior, and the OAuth guidance.
- **Root `CLAUDE.md`:** correct the sentence about registering both `:5173` and `:8080` URIs.
- **`backend/README.md`:** the redirect URI table and the GitHub single-callback caveat.
- **`backend/specs/security/design.md`:** the register-existing-account behavior and the reason `409` is not used.

---

## 7. Acceptance criteria

**Item 1: test filter**
- [ ] `test` is present on every node in `/graph`, on `/nodes/{id}`, on `/files` items and on `/files/{id}`, correct for the §1.2 rules, on **existing** analyses too.
- [ ] `GET /files?test=true|false` filters before pagination with correct `totalElements`/`totalPages`. An invalid value is a `400 VALIDATION_ERROR`.
- [ ] The Architecture tab has the Working | Tests | All control, defaulting to Working. `Tests` is strict. Edges follow the both-ends-visible rule. `scope` lives in the URL and is omitted at the default.
- [ ] Relationships (default Working) and Files (default All) each have their own control and URL param. The relationship detail panel is unfiltered but badges test items. A hidden deep-linked selection stays visible with a notice.
- [ ] Empty states exist for no tests and no working code. Counts are shown. The control works for function-oriented repositories.
- [ ] Available to FREE users (no `PREMIUM_FEATURE`), with no change to `GraphPremiumGate`.

**Item 2: OAuth**
- [ ] The report prints the exact `redirect_uri` values observed from the real authorization redirects, through the proxy and directly.
- [ ] Any code or config defect is fixed, with backend and e2e regression tests, and the misleading docs are corrected.
- [ ] The report lists the exact Google and GitHub console changes (§2.3), and says which were confirmed against the observed redirect.

**Item 3: duplicate email**
- [ ] `register` returns the same status and body for new and existing emails. No `409` and no "already registered" text on any page.
- [ ] The existing owner gets the right email (verified, OAuth-only, unverified-retry) at most once per hour, and nothing for disabled accounts. All asynchronous.
- [ ] Re-registering an unverified account replaces its name and password and sends a fresh link. The old password stops working.
- [ ] The check-email screen copy is true for both cases and links to login and forgot-password.

**Item 4: dragging**
- [ ] Every node is draggable, and edges follow. A click still inspects. A drag doesn't change `?node=`.
- [ ] Positions survive filter and scope changes for visible nodes, reset on reload, and the **Reset layout** button restores the automatic layout.
- [ ] Multi-select group drag, arrow-key nudging (with an announcement) and touch drag work. No page scroll during a node drag. The minimap and fullscreen reflect the positions.
- [ ] Dragging stays smooth on the largest local analysis.

**General**
- [ ] `./mvnw clean test` and `npm run verify` pass. Playwright e2e passes where runnable, with skips reported. `check:tokens` and the Hallmark sloplint pass.
- [ ] All documentation in §6 is updated.

---

## 8. Out of scope
Annotation/framework-based test detection, persisting node positions (browser or backend), a `/graph` query parameter for tests, Overview working/test counts, changing the premium gate, returning `409` for duplicate registration, and any mock data.

---

## 9. Open points: decide sensibly and report
1. Whether `Method`/`Function` nodes carry `file` in Neo4j, or the flag must come from their containing file.
2. How the module test flag is computed (extra Cypher read vs. derived from data already fetched).
3. Which React Flow props or default keyboard behaviors already cover group selection and nudging in the installed version.
4. Whether anything in the proxy or backend config (not just the provider consoles) was actually causing the `redirect_uri` mismatch.
5. Any existing throttle you reused for the unverified-account verification email.

---

## 10. Final report (output when done)
1. What changed, grouped by backend, frontend, and docs.
2. The exact `test` field/parameter contract as implemented, and the final classifier rule table.
3. **OAuth:** the observed `redirect_uri` values (proxy and direct), any defect found and fixed, and the exact manual console changes still needed.
4. The final register behavior per account type, the throttle, and anything notable about the unverified-account retry.
5. Drag behavior as built (gestures, keyboard step sizes, touch handling) and the performance you measured.
6. Test and verification results: `./mvnw clean test` counts, `npm run verify`, e2e (run and skipped), `check:tokens`, sloplint.
7. Decisions from §9 and anything you chose without being told.

---

## 11. Suggested order of work
1. **Inspect and probe.** Read the docs and relevant code. Start the stack. Capture the real OAuth redirects (§2.2 steps 1–2). Check the Neo4j data for `file` on methods and functions.
2. **OAuth fix and tests** (§2): it unblocks sign-in for the later e2e work. Don't wait for my console changes; list them and continue.
3. **Backend test flag:** classifier, graph/node/file fields, `/files?test=`, fixtures, tests (§1.2–1.4).
4. **Backend duplicate registration** (§3.2, §3.4).
5. **Frontend scope filter:** types and probe, adapter, control, Architecture, Relationships, Files, tests (§1.5–1.8).
6. **Frontend dragging** (§4).
7. **Frontend duplicate-email copy** (§3.3).
8. **Docs** (§6).
9. **Full verification** and the final report.