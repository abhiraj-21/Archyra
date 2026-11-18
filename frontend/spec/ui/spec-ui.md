# Archyra Frontend: Build Spec (real backend, Hallmark design)

> **For Claude Code, run together with the Hallmark design skill (`nutlope/hallmark`).**
>
> This spec describes a **complete, backend-integrated** frontend for Archyra. It talks to the real Spring Boot API under `/api/v1`. **There is no mock data, no mock client, no simulator and no dev mock panel.** Real auth, real analyses, real graphs, real usage limits, and a role-gated admin area.
>
> **Read first:** this file, `Architecture.md`, the root `CLAUDE.md`, `backend/CLAUDE.md`, `frontend/CLAUDE.md` and `backend/specs/security/design.md` (and `spec.md` next to it). `frontend/CLAUDE.md` still says the frontend is "deferred". That is outdated and this spec supersedes it. You will update that file at the end (§19).
>
> **Four mandatory rules (details in the sections named):**
> 1. **Human-in-the-loop theme selection, `Blueprint` banned.** You may not pick a theme. You present three candidates and **stop until I choose** (§3).
> 2. **Zero mock data, real integration** through a Vite proxy and TanStack Query, exact contracts from `Architecture.md` (§6–§7).
> 3. **Admin area at `/admin`**, gated strictly on the `ADMIN` role from `GET /api/v1/users/me` (§11).
> 4. **Self-contained local images** in `frontend/src/assets/`, produced from the real rendered app and imported with static Vite imports (§13).

---

## 0. Ground rules

- **The backend source is in this repository (`backend/`).** `Architecture.md` is the contract, but it is silent or loose in places (e.g. the history item shape, auth request bodies, `/users/me` fields, admin DTOs, exact error codes). **Read the controllers, DTOs and the central `@RestControllerAdvice` to get exact shapes and codes. Do not guess.** Where code and `Architecture.md` disagree, build against the **running backend**, and list every discrepancy in your final report.
- **Do not modify the backend.** If the frontend needs something the backend doesn't provide (e.g. `role` missing from `/users/me`, no technologies endpoint), **stop and report it** instead of working around it with invented data, and do not change backend code.
- **Verify by probing the real API** (curl or the browser) before typing any response interface. Types you write must match what you actually observed.
- **Never fabricate data in the UI.** If the backend doesn't return something, the UI doesn't show it. It may show an honest "not available yet".
- Handle every error **by its machine-readable `code`**, never by matching message text.
- Treat all API-returned strings (repository names, URLs, node/class names, file paths, `failureReason`, user names/emails) as **untrusted text**: render as text only, never as HTML (no `dangerouslySetInnerHTML`), and only make links from values you've validated (e.g. `https://github.com/...`).

---

## 1. Product, tone, honesty

Archyra ("the architecture behind your code") takes a GitHub repository, analyzes it statically (no AI required), builds a code graph (files, classes, methods, imports, calls, inheritance) and renders it as an explorable architecture diagram. AI explanations come later (V1). Audience: developers trying to understand a codebase quickly. Tone: precise, calm, technical, not hype-y.

**Copy honesty:** claim only what `Architecture.md` says the product does today. **No fake testimonials, customer logos, user counts or metrics.** Unbuilt features (Ask AI) are labeled *coming soon*. Payments are not built, so the Upgrade action is honest about that.

---

## 2. Scope

**Build:** landing page; auth (email/password, Google, GitHub, verification, password reset); app shell; Dashboard (submit, history, usage); analysis progress; repository workspace (Overview, Architecture, Files, Relationships, Ask AI teaser); settings; upgrade dialog and locked features; **admin area**; image assets and the capture tooling that produces them.

**Not in scope:** payments/checkout, the AI chat, V2/V3 graph interactions (shown only as locked/coming-soon affordances where the backend doesn't support them yet), any backend change.

**Capability map (check the backend to confirm each one before wiring):**

| State | Examples |
|---|---|
| **Built, wire it** | `analyses` (create, list, status, delete, reanalyze), `repository`, `graph` (with `nodeType`, `relationshipType`), `nodes/{id}`, `files`, `files/{id}`, `classes/{id}`, `usage`, `users/me`, auth routes, `admin/users`, `admin/users/{id}`, `PATCH admin/users/{id}/status`, `admin/stats` |
| **Premium-gated, backend decides** | `graph` with other parameters or `level` other than `BEGINNER` → `403 PREMIUM_FEATURE` for FREE |
| **Not built → "coming soon", never called** | `chat`, `explain`, `architecture`, `conversations`, `nodes/{id}/relationships`, `nodes/{id}/expand`, `classes/{id}/methods`, `methods/{id}/dependencies`, and any `graph` parameter the backend doesn't implement yet |
| **Data the backend stores but doesn't expose** | Technologies/framework detection (stored in `analysis_metadata`). Show it **only if** `/repository` actually returns it; otherwise omit the section and report the gap |

---

## 3. Visual direction and the theme gate (human-in-the-loop)

### 3.1 The ban
**The `Blueprint` theme is banned.** Not used, not suggested, not offered as a candidate. The ban extends to the *look*: **no cyan or blue drafting-paper aesthetic and no engineering-grid / graph-paper look**, in the theme, the graph canvas, the landing page or the images. Concretely:
- No grid-line, cross or dot-grid pattern as a page or canvas background (that includes React Flow's `<Background>` with grid variants). The canvas is a plain themed surface.
- No drafting motifs (rulers, compasses, blueprint-style callouts, white-on-blue line art).
- Blue or cyan *as a color* is not banned, but the drafting-paper treatment is.
- If Hallmark's theme deal returns `Blueprint`, or any theme whose polarity, surface or palette amounts to the banned look, **discard it and deal a replacement.** Repeat until the three candidates are all clear of it.

Even though the product is about architecture, I don't want it to look like an architect's blueprint.

### 3.2 The pause (mandatory)
**Hallmark does not choose the theme. I do.**
1. Run Hallmark's design flow and its theme deal step with the brief in §3.3.
2. Apply the ban above. Assemble **exactly three** non-blueprint candidates from Hallmark's catalog that fit the brief.
3. **Stop and present them to me** in a short, scannable comparison. For each: **name**, **polarity** (light, dark, or both), **typography** (the type character and pairing, e.g. serif display + mono for code; naming the actual typefaces is fine *in this conversation only*), **surface style** (paper/texture, borders, shadows, density), and why it would suit a developer tool centered on a graph canvas. Add one line on how each would handle semantic graph colors.
4. **Wait for my explicit choice.** Do **not** scaffold pages, write components, capture images or proceed with any visual work until I answer. If I reject all three, deal three new non-blueprint candidates and ask again.
5. Only after my reply, continue.

### 3.3 Brief for Hallmark
A precise, trustworthy developer tool about software architecture: structure, relationships, clarity. The **graph canvas is the hero** of the product and gets the most room and care. **Balanced density**: comfortable reading size and moderate spacing generally; dense only inside the graph, tables, inspector and class browser. Must work well at desktop and 390px widths. Must support a legible semantic color system for graph node and edge types (§8.6).

### 3.4 After my choice
- Record the selection in **`frontend/DESIGN-NOTES.md`** (under one page): the theme name, polarity/mode(s), where the exported tokens live, the candidates that were offered, a line saying **"`Blueprint` and blueprint/engineering-grid aesthetics are banned"**, and any deviations with reasons.
- **Consume Hallmark's CSS tokens everywhere** (wire them into Tailwind's theme). **Zero raw hex codes, `rgb()/hsl()` literals or font names in components or feature CSS.** Only the token file may contain color and font values.
- Add an automated check, `npm run check:tokens`, that fails on hex/`rgb(`/`hsl(`/`font-family` literals anywhere in `src/` except the token file, and include it in the standard verification script.
- Record the ban in `frontend/CLAUDE.md` too, so future sessions can't quietly reintroduce it.
- If Hallmark's chosen theme ships light and dark modes, both must be fully designed. No half-built mode.
- Run Hallmark's pre-ship gates at the end and fix every finding. Never override a gate.

---

## 4. Tech stack and structure

- React + **TypeScript (strict)**, **Vite**, **Tailwind**, **React Router**, **TanStack Query** (all server state), **React Flow** (`@xyflow/react`).
- **Layered layout:** compute graph positions client-side with one layered-layout library (dagre or ELK; choose one and justify it in `DESIGN-NOTES.md`) so dependencies read top to bottom (controller → service → repository).
- **Playwright** is used for the asset-capture scripts (§13) and for the end-to-end checks (§16).
- **Suggested structure** (adapt, but keep the separation):

```
frontend/
  spec-ui.md  DESIGN-NOTES.md  CLAUDE.md  vite.config.ts  .env.example
  scripts/    capture/  (asset capture + e2e helpers; never imported by the app)
  src/
    api/        http.ts (fetch wrapper)  auth.ts (token + refresh)  errors.ts  types.ts  queryKeys.ts  endpoints/*.ts
    hooks/      one query/mutation hook per endpoint (components use only these)
    features/   landing  auth  dashboard  analysis-progress  repository/{overview,architecture,files,relationships,ask-ai}
                usage  upgrade  settings  admin
    components/ ui/  layout/  graph/ (adapter, styles, canvas, inspector)
    routes/     router.tsx  guards.tsx
    lib/        url.ts (repo URL validation)  encode.ts (node id encoding)  format.ts
    assets/     (images, §13)  README.md
```

### Vite proxy and environment
- `vite.config.ts` proxies **`/api`**, **`/oauth2`** and **`/login/oauth2`** to the backend origin (env `VITE_BACKEND_ORIGIN`, default `http://localhost:8080`). Use `xfwd`/forwarded headers so the backend builds correct OAuth2 redirect URIs. Browser code only ever uses **relative URLs** (`/api/v1/...`), so the app is same-origin in dev and the `SameSite=Strict` refresh cookie works.
- No secrets in `VITE_*` variables (they ship to the browser). `.env.example` lists the variables used.
- **Integration checks to perform and report** (don't assume): that the backend's `frontend-base-url`, `server.forward-headers-strategy` and the Google/GitHub registered redirect URIs line up with a proxied dev origin (`http://localhost:5173`). If OAuth can't work through the proxy without a backend or provider-console change, report exactly what is needed. Also check whether the `XSRF-TOKEN` cookie is readable by the SPA in dev, and how it will be in production. For production, recommend serving the SPA and API on one origin behind a reverse proxy.

---

## 5. Routes

| Route | Access | Screen |
|---|---|---|
| `/` | public | Landing |
| `/login`, `/register`, `/register/check-email` | public | Auth |
| `/verify-email?token=` | public | Verification result |
| `/forgot-password`, `/reset-password?token=` | public | Password reset |
| `/auth/callback` | public | OAuth return |
| `/dashboard` | auth | Submit + history + usage |
| `/analyses/:analysisId` | auth | Progress view while not `COMPLETED`; redirects to Overview when `COMPLETED` |
| `/analyses/:analysisId/{overview,architecture,files,relationships,ask-ai}` | auth | Workspace tabs |
| `/settings` | auth | Account |
| `/admin` → `/admin/stats` | **ADMIN** | Admin stats |
| `/admin/users` | **ADMIN** | Users table |
| `/admin/users/:userId` | **ADMIN** | User detail |
| `*` | any | Designed 404 |

- **Auth guard:** unauthenticated → `/login` (remember the destination). Authenticated users on `/login`/`/register` → `/dashboard`.
- **Admin guard:** see §11. Non-admins who hit `/admin/**` get the **same 404 screen as any unknown route** (no hint that an admin area exists).
- **Shell:** slim left sidebar (collapsible to icons) with the Archyra mark, **Dashboard**, **Settings**, the **usage meter**, the user menu (name, plan badge, sign out), and **an Admin link rendered only for `ADMIN` users** (not disabled, not greyed: absent for everyone else). Repository pages add a header (name, status, language chips, Re-analyze, overflow menu with Delete) and a tab bar: **Overview · Architecture · Files · Relationships · Ask AI**.
- Tab, node selection and filters live in the URL (deep-linkable). An analysis id that is unknown or **not yours** shows the not-found state (the backend returns 404 for another user's analysis; never imply it exists).

---

## 6. Data layer and real authentication

### 6.1 Fetch layer
- One `http()` wrapper over `fetch`: relative `/api/v1/...` URLs, `credentials: 'include'`, JSON in/out, `Authorization: Bearer <accessToken>` when a token is held, and normalization of every failure into `ApiError { status, code, message, retryAfter? }` (parse the `{ code, message }` body; `Retry-After` from the header).
- **TanStack Query** for everything server-side. **Components use only hooks** in `src/hooks/`. They never call `fetch` or import from `src/api/` directly.
- **Query keys** in one file. The graph key includes **every** query parameter (`nodeType`, `relationshipType` now; the V2/V3 parameters later) so future parameters slot in.
- Queries for repository results (`/repository`, `/graph`, `/files`, …) are **enabled only when the analysis is `COMPLETED`**.
- Every node/class/file id used in a URL path goes through one `encodeNodeId` helper that percent-encodes the whole id (ids contain `/ # ( )` and spaces; the server accepts the encoded slash). Unit-test it.

### 6.2 Session handling
- **Access JWT lives in module memory only.** Never `localStorage`/`sessionStorage`/cookies written by JS. Never log it.
- **The refresh token is an HttpOnly cookie** the JS cannot see. Refresh and logout need CSRF: call `GET /api/v1/auth/csrf` once (sets the `XSRF-TOKEN` cookie), then send its value in the **`X-XSRF-TOKEN`** header on `POST /auth/refresh` and `POST /auth/logout`.
- **Boot sequence:** show a designed loading screen → `csrf` → `refresh`. On success, store the token and load `GET /users/me`, then render. On `401`, the user is anonymous. No flash of the wrong screen.
- **Automatic 401 handling:** on `401 AUTHENTICATION_REQUIRED` for a non-auth request, run **one single-flight refresh** shared by all concurrent requests, then **retry the original request once**. If the refresh fails, clear the session and query cache and go to `/login` (with the destination remembered). Never loop. Never try to refresh for `login`, `register`, `refresh` or `logout` calls themselves.
- **Logout:** `POST /auth/logout` (with CSRF), clear the token, clear the whole query cache, go to `/login`.
- **Current user:** `GET /api/v1/users/me` is the single source for name, email, **role** and plan data. Verify the exact field names against the backend DTO and the running API. If `role` isn't present, **stop and report** (§11).

### 6.3 Auth flows (all real)
Verify each request/response body against the backend DTOs.
- **Register** → `POST /auth/register` → always the generic 202, then the "check your inbox" screen. The UI **never reveals** whether an email exists.
- **Login** → `POST /auth/login` → access token response → load `/users/me` → `/dashboard`. Map `INVALID_CREDENTIALS`, `EMAIL_NOT_VERIFIED` (offer resend), `ACCOUNT_DISABLED`, `RATE_LIMITED` (use `Retry-After`, disable the form until it elapses).
- **Google / GitHub:** a **full-page navigation** (not `fetch`) to `/oauth2/authorization/google` or `/oauth2/authorization/github` (through the proxy). The backend finishes by redirecting to `/auth/callback` (success) or `/login?error=<code>`. `/auth/callback` runs `csrf → refresh → /users/me` and goes to the remembered destination or `/dashboard`. **No token ever appears in a URL.** Map the `error` codes the backend actually emits (read the backend; expected: `oauth_failed`, `oauth_email_unverified`, `oauth_account_disabled`, `oauth_cancelled`) to friendly messages, with a generic fallback. **Never render the raw query value.**
- **Verify email** (`/verify-email?token=`) → `POST /auth/verify-email`. Tokens are **single-use**, so guard against double submission (React StrictMode double-effects, re-renders): fire exactly once. Show success or an expired/invalid state with a resend option (`POST /auth/resend-verification`, always-generic 202, with a cooldown).
- **Forgot password** → `POST /auth/forgot-password` (always generic confirmation). **Reset** (`/reset-password?token=`) → `POST /auth/reset-password`; success → login with a notice that all sessions were ended.
- Remove `token` query parameters from the address bar right after reading them (`history.replaceState`) and set a `Referrer-Policy` of `no-referrer`-equivalent behavior on those pages (via meta tag).
- Confirm the **email links' paths** the backend generates (`{frontend-base-url}/verify-email?token=…`, `/reset-password?token=…`) match the routes here, and report any mismatch.

### 6.4 Error handling by code
One `errorCopy` map from `code` to UI behavior and copy; unknown codes get a generic message. At minimum (confirm each against the backend advice; add what you find):
`AUTHENTICATION_REQUIRED` (401, triggers refresh), `ACCESS_DENIED`, `CSRF_TOKEN_INVALID` (refetch the CSRF token and retry once), `PREMIUM_FEATURE` (open the upgrade dialog naming the feature), `USAGE_LIMIT_EXCEEDED` (limit-reached state), `RATE_LIMITED` (429 + `Retry-After` countdown), `SERVICE_BUSY` (503, "the queue is full, try again shortly"), `INVALID_CREDENTIALS`, `EMAIL_NOT_VERIFIED`, `ACCOUNT_DISABLED`, `VALIDATION_ERROR` (400, show the listed allowed values where provided), `ANALYSIS_NOT_FOUND` / `NODE_NOT_FOUND` (404), `ANALYSIS_NOT_COMPLETED` (409), `ANALYSIS_IN_PROGRESS` (409 on delete), `CANNOT_DISABLE_SELF` / `LAST_ADMIN` (409, admin).
Network failures and timeouts get a retryable error state (no raw exceptions shown).

### 6.5 Mutations and cache rules
- **Create analysis** (`POST /analyses`) → `202 { analysisId, status }`, navigate to `/analyses/:id`, invalidate history and **usage**. If the user submits a URL already running, the backend returns the **existing** analysis: just navigate to it.
- **Delete** → invalidate history and usage (deleting returns the credit to free users; the UI doesn't compute this, it refetches `/usage`). Running analyses can't be deleted (`409 ANALYSIS_IN_PROGRESS`).
- **Re-analyze** (`POST /analyses/{id}/reanalyze`) → `202` with the **new** analysis id; navigate to its progress view. Explain in the UI that the old analysis is replaced when the re-run completes. At the free limit the backend refuses with `USAGE_LIMIT_EXCEEDED`.
- When polling observes `FAILED` (credit returned) or any terminal state, invalidate usage and history.
- **Polling:** `GET /analyses/{id}` with `refetchInterval` (about 2 s) that returns `false` at `COMPLETED`/`FAILED`; keep trying through transient errors with backoff; don't poll in hidden tabs.

---

## 7. Types and contracts

`src/api/types.ts` is hand-written, mirrors `Architecture.md`, and is **verified against the backend DTOs and live responses**. Notable shapes (confirm exact fields):

```ts
type AnalysisStatus = 'QUEUED'|'CLONING'|'PARSING'|'ANALYZING'|'BUILDING_GRAPH'|'COMPLETED'|'FAILED';
type NodeType = 'REPOSITORY'|'MODULE'|'FILE'|'CLASS'|'INTERFACE'|'METHOD'|'FUNCTION';
type EdgeType = 'IMPORTS'|'CONTAINS'|'CALLS'|'DEPENDS_ON'|'EXTENDS'|'IMPLEMENTS'|'REFERENCES';
type UsageType = 'REPOSITORY_ANALYSIS'|'AI_QUERY';
```
- Create: `{ analysisId, status }`. Status: `{ analysisId, status, progress, failureReason? }` (`failureReason` only when `FAILED`).
- **History** `GET /analyses?page&size` (the caller's own, newest first; no `status` filter yet): read the item shape from the backend. `Architecture.md` doesn't define it.
- **Repository** `{ name, languages[], files, classes, methods }`, plus whatever else the backend returns (language stats, technologies). Only render what's there.
- **Graph** `{ nodes: [{ id, type, name, file?, ... }], edges: [{ source, target, type }] }`. External nodes and edges touching them are never returned; `file` is omitted for nodes without one.
- **Node** `{ id, type, name, qualifiedName?, file?, line?, kind?, language?, parentId?, relationships: { outgoing: {...}, incoming: {...} } }` (absent fields are omitted).
- **Files** paged `{ items:[{id,name,path,language}], page, size, totalElements, totalPages }` (default size 50, max 200; filters `language`, `path`). **File** with `module, classes, interfaces, methods, functions, imports, references` (lists always present; targets may be `external: true`).
- **Class** `{ id, name, qualifiedName, kind, file, line, parentId, superclasses[], methods[], interfaces[], dependencies[] }` (interface ids are `404 NODE_NOT_FOUND` here: use `/nodes/{id}`).
- **Usage** `{ plan, usage: [{ usageType, used, limit, remaining, limitReached }] }`. For PREMIUM, `limit` and `remaining` are `null` and `limitReached` is `false`.
- **Admin stats** `{ totalUsers, activeUsers, premiumUsers, totalAnalyses }`. **Admin users** paged (`page`, `size` default 50); read the DTO.
- **Error** `{ code, message }`.

---

## 8. Screens and flows

### 8.1 Landing page (`/`)
Hallmark shapes the structure within the chosen theme. Required content:
- **Hero:** the promise ("The architecture behind your code"), a plain explanation, CTAs **Analyze a repository** (to `/login` or `/register` when signed out, `/dashboard` when signed in) and **Sign in**, with the **`hero.png`** image (§13).
- **How it works:** repository → static analysis → code graph → understanding, honest and concrete.
- **Product tour** using real screenshots (**`workspace-preview.png`**, **`graph-layered.png`**, **`inspector-panel.png`**, **`files-table.png`**, **`relationships-browser.png`**) with accurate captions.
- **What you get now** vs **Coming soon** (Ask AI, labeled clearly).
- **Languages** analysed (from `Architecture.md`), noting that others are detected and counted.
- **Free vs Premium** comparison from `Architecture.md` (one shared data source with the upgrade dialog).
- Honest footer. No fabricated social proof.
- Respect §3.1: no blueprint/grid look anywhere on this page.

### 8.2 Auth screens
Login (email/password + **Continue with Google** + **Continue with GitHub**), register, check-email, verify-email, forgot/reset password, `/auth/callback`, and `/login?error=` messaging. All real (§6.3). Forms: validation on blur and submit, accessible error association, disabled-while-submitting, no information leaks, and password length hints (8–128; the backend hashes with bcrypt, so passwords over 72 bytes are rejected or limited, so read the backend rule and show it).

### 8.3 Dashboard
- **Prominent "Paste a GitHub URL" input** with **Analyze** (Enter submits). No separate modal.
- Client-side URL check as a convenience only (the server is the authority): `https://github.com/{owner}/{repo}`, optional trailing slash or `.git`, no credentials, no other hosts. Specific inline hints for common mistakes. Unit-test it.
- **Usage meter** next to it (§10). At the free limit the input is disabled with an explanation and an Upgrade action. `403 USAGE_LIMIT_EXCEEDED` on submit is handled too.
- **History** from `GET /analyses` (page size 20, pagination controls): repository name, URL, status chip, live progress for running ones, languages where the response has them, date, and actions: **Open**, **View progress**, **Re-analyze**, **Delete** (confirm dialog; running ones explain why they can't be deleted).
- Designed empty (first-run), loading (skeleton) and error (retry) states.

### 8.4 Progress view (`/analyses/:id` while not `COMPLETED`)
- A **stepper** (QUEUED → CLONING → PARSING → ANALYZING → BUILDING_GRAPH) with the active stage described in plain language, and a **progress bar** from the real `progress` value (accessible to screen readers).
- A **decorative, abstract animation** (it may suggest growing structure but must contain **no fake repository names, numbers or graph data** and must be obviously not data). Reduced-motion users get a static equivalent.
- On **`COMPLETED`**: a brief completion moment, then `replace` navigation to Overview.
- On **`FAILED`**: show the real `failureReason`, a note that the credit is returned, **Try again** (new analysis) and **Back to dashboard**.
- Unknown/not-yours → not-found state. Revisiting a `COMPLETED` id goes straight to Overview.

### 8.5 Repository workspace
**Overview:** stats from `/repository`; **languages breakdown** (each language with its count, noting which are analysed vs only counted, if the response says so); **technologies only if returned by the API**; a **non-interactive architecture preview** that links to the Architecture tab.

**Architecture (hero screen):**
- **Default view:** the **layered class graph, top to bottom**: classes and interfaces joined by `DEPENDS_ON`, reading controller → service → repository. The canvas gets maximum room (the sidebar can collapse). Plain themed canvas, **no grid or dot pattern** (§3.1).
- **Data:** fetch the graph once, **filter client-side** (the V0.1 `nodeType`/`relationshipType` parameters take one value each, so multi-select toggles can't map to them). The query key still carries the parameters. Use React Flow's visible-elements rendering so a large graph, including methods, stays smooth. Never freeze on big repos.
- **Live controls:** node-type filter (Class, Interface, Method, Function, File, Module; default Class + Interface), relationship-type filter (`DEPENDS_ON`, `CALLS`, `IMPORTS`, `EXTENDS`, `IMPLEMENTS`, `REFERENCES`, `CONTAINS`; sensible default), **zoom in/out, fit-to-screen, minimap, legend, fullscreen**. An edge shows only when both ends are visible. Filter state is in the URL.
- **Click a node:** select it, highlight its direct connections and dim the rest, open the **docked right inspector** (from `GET /nodes/{id}`: name, type/kind, qualified name, file and line, parent, relationship counts by type) with **Open in Files** and **Open in Relationships** actions. Empty-canvas click or Escape clears it. Keyboard: nodes are focusable, Enter selects.
- **Locked affordances:** graph **level** selector (Beginner live; Intermediate/Expert per §9), search, expand/enter node, depth and advanced filters, shown per the three-state rule in §9.
- **Edge cases:** filters that hide everything show an empty state with *Reset filters*. Repositories without classes (function/module languages) default to a sensible module/function view instead of an empty canvas. External nodes never appear (the API excludes them).
- **Accessible alternative:** a keyboard- and screen-reader-friendly **node list** of the same data.

**Files:** paginated table (name, path, language) from `GET /files` with **language** and **path** filters (path matches whole segments) and page size controls; counts like "Showing 1–50 of 143"; filters in the URL. **Row click opens a drawer** from `GET /files/{id}`: module, classes, interfaces, methods, functions, imports (internal ones link to their file, external ones are marked), references. Note that counted-only languages (SQL, shell, …) have no files here.

**Relationships:** a **class browser**. A searchable list of classes and interfaces comes from the already-fetched graph (CLASS and INTERFACE nodes). Selecting a **class** loads `GET /classes/{id}` (extends, implements, depends-on, methods); selecting an **interface** loads `GET /nodes/{id}`. **"Depended on by"** is derived from the loaded graph's incoming `DEPENDS_ON` edges. Items navigate within the browser; external targets are marked and not clickable. Repositories with no classes show a clear explanation. Deep exploration (depth, method-level dependencies) is **coming soon / PRO** per §9. Selection lives in the URL.

**Ask AI (V1):** visible tab, honest **"Coming soon"** state, the example questions from `Architecture.md`, a disabled input, and a clearly labeled preview of how sources will appear as clickable citations. Calls nothing.

### 8.6 Graph styling
Define node and edge styles **once** in a `graphStyles` module keyed by `type`, derived from Hallmark tokens. Color is **never the only signal**: node types also differ by shape or icon, edge types by line style/marker (e.g. solid for `DEPENDS_ON`, dashed for `CALLS`, marked ends for `EXTENDS`/`IMPLEMENTS`). The legend reads from the same module.

### 8.7 Settings
`GET/PATCH /users/me` (name only, with validation and success/error feedback), email (read-only), plan badge with an Upgrade action for FREE, linked sign-in providers **if the API returns them**, the usage section, and Sign out.

---

## 9. Locked features: three states

Distinguish clearly, with one shared component each:
1. **Available**: works now.
2. **PRO-locked**: the backend supports it but the plan doesn't. Shows a consistent **PRO** badge; clicking opens the **Upgrade dialog** naming the feature. If the request is made anyway, handle `403 PREMIUM_FEATURE` the same way.
3. **Coming soon**: the backend doesn't have it yet (Ask AI, V2 endpoints, any unsupported graph parameter), **for every plan**. It is never called, and a PREMIUM user doesn't see a PRO lock on it, because it isn't a plan problem.

Decide each control's state by **reading the backend**, not by assumption, and list them in the final report. A PREMIUM user gets every control the backend supports; nothing is faked.

**Upgrade dialog:** the free-vs-premium comparison from `Architecture.md`. Payments aren't built, so the primary button is an honest *"Premium is coming soon"* state. There is no fake checkout.

---

## 10. Usage UI
- **Meter** (e.g. `3 / 5 analyses`) in the sidebar and on the dashboard, from `GET /usage`. PREMIUM shows *Unlimited* (limit `null`) with the true count. Accessible, with text.
- **Warning** when one remains. **Limit-reached state** when `limitReached` (FREE): analyze input disabled with an explanation and an Upgrade action; mention that deleting an analysis returns its credit.
- Refetch `/usage` after create, delete, re-analyze and when polling sees `FAILED`. Never compute usage client-side.

---

## 11. Admin area (`/admin`)

**Gating (strict):**
- Determine admin status **only** from the `ADMIN` role in `GET /api/v1/users/me` (verify the field name against the backend). Do not infer it from the token, local state, URL, or anything else.
- If `/users/me` doesn't expose the role, **stop and report**. Don't guess and don't edit the backend.
- The **Admin sidebar link and every `/admin/**` route** render only for `ADMIN`. Others never see the link (it is absent, not disabled), and hitting `/admin/**` shows the **ordinary 404 screen**.
- While `/users/me` is loading, show a neutral loading state (no admin UI flash).
- This is a UX gate only. The **backend enforces** admin access. If an admin call returns `403 ACCESS_DENIED` (e.g. the role changed), refetch `/users/me`, drop the admin UI and show the 404 screen.

**Screens:**
- **`/admin/stats`** → `GET /api/v1/admin/stats`: total users, active users (signed in during the last 30 days), premium users, total analyses, as clear stat blocks with plain labels and loading/error states.
- **`/admin/users`** → `GET /api/v1/admin/users?page&size=50`: paginated table with the columns the DTO provides, an enabled/disabled indicator, and a link to the detail page.
- **`/admin/users/:userId`** → `GET /api/v1/admin/users/{userId}`: details from the DTO, and an **Enable/Disable** action using `PATCH /api/v1/admin/users/{userId}/status` with `{ "enabled": boolean }`. Disabling needs a **confirmation dialog** stating that it ends all of the user's sessions. Handle `409 CANNOT_DISABLE_SELF` and `409 LAST_ADMIN` by code with clear messages. Invalidate the user and list queries on success.
- Admin has **no access to other users' analyses or code graphs** (the backend enforces ownership), so the UI provides none.
- **Privacy:** admin screens show real user emails. They must **never appear in captured assets** (§13) or logs.

---

## 12. Security rules for the frontend
- Tokens: memory only; never persisted, logged or placed in URLs. Redact tokens from error reports.
- Render API strings as text (§0). Links only from validated values, with `rel="noopener noreferrer"`.
- No inline scripts or `eval` (keeps a strict CSP possible). No secrets in `VITE_*`.
- Sign-out clears the query cache so no data survives to the next user.
- CSRF header on `refresh` and `logout` (§6.2).

---

## 13. Image assets (`frontend/src/assets/`)

**Rules**
- **No placeholder boxes, no stock hotlinks, no remote image URLs.** Every raster image is a **local `.png`** in `frontend/src/assets/`.
- Import every image with a **standard Vite static import** (`import hero from '@/assets/hero.png'`) so that **replacing an image later means overwriting the file with the same name**. Don't build image URLs from strings, and don't reference these images from `public/`. Hard-coded dimensions must not depend on the image: containers use flexible sizing and `object-fit`, so a replacement with different dimensions doesn't break the layout. Every `<img>` has accurate alt text.
- Logos, marks and icons may be inline SVG components or SVG files (they're vector, not product imagery). The favicon is a PNG generated from the mark.
- Respect §3.1: nothing in the images may use the banned blueprint/grid aesthetic. Captures use the **chosen theme**.

**How to produce them (real app, real data)**
- Add a committed script (`npm run assets:capture`, in `scripts/capture/`, never imported by the app) that drives **Playwright (Chromium, `deviceScaleFactor: 2`)** against the **running app and real backend**, signs in with credentials from untracked env vars (never committed), and uses a real analysis of a public repository (for example `spring-petclinic`, which `Architecture.md` already references).
- It saves PNGs with **clean, predictable names** into `frontend/src/assets/`, using a stable window size and theme, with animations settled before the shot.
- **Capture only your own non-sensitive data.** No admin screens, no real emails, no tokens, no other users' data. The signed-in user's visible name/email in any shot must be a test account.
- **Fallback if a full capture isn't possible:** render the real `GraphCanvas` component in an isolated capture harness (a Playwright page under `scripts/capture/`, not part of the app bundle) fed by a **real `/graph` response recorded once from the running backend** and saved as a JSON file in `scripts/capture/`. Do **not** hand-write a fake graph. If neither route works because no backend can be run, **stop and ask me** for a running backend or credentials.

**Required files** (add more if the design needs them)

| File | Content | Used on |
|---|---|---|
| `hero.png` | The layered architecture graph, composed for the hero | Landing hero |
| `workspace-preview.png` | Full workspace: sidebar, header, tabs, graph with the inspector open | Landing tour, headline preview |
| `graph-layered.png` | Close-up of the layered graph canvas | Landing tour |
| `inspector-panel.png` | Node inspector with relationship counts | Landing tour |
| `files-table.png` | Files tab with the detail drawer | Landing tour |
| `relationships-browser.png` | Class browser with relationship detail | Landing tour |
| `analysis-progress.png` | Progress view mid-run | Landing "how it works" |
| `favicon.png` | Generated from the Archyra mark | `index.html` |

- Each PNG is crisp at 2×, at least **1600 px** wide for full-width uses, and optimized (target well under 600 KB each; use an optimizer such as `sharp` or `oxipng`).
- Add **`frontend/src/assets/README.md`** listing every asset: filename, pixel dimensions, what it shows, where it's used, and how to regenerate it.
- **The final report must list every created asset file, its dimensions and where it appears.**

---

## 14. Responsive, accessibility, motion
- **Desktop-first, graceful on tablet and mobile.** On small screens the sidebar becomes a drawer, tabs scroll horizontally, the inspector becomes a **bottom sheet**, tables stay readable, and the graph remains pannable and zoomable by touch. Nothing is unusable at 360 px.
- **Accessibility:** WCAG 2.2 AA contrast, full keyboard operation, visible focus, landmarks and headings, labelled controls, **live-region announcements** for progress, errors and toasts, and **no color-only meaning** (§8.6). The graph has the node-list alternative.
- **Motion:** restrained and purposeful; honor `prefers-reduced-motion` everywhere with static equivalents.
- Every data view has designed **loading, empty, error (with retry) and not-found** states.

---

## 15. No-mock policy (explicit)
- The repository must contain **no** `mockClient`, mock data stores, simulators, MSW handlers, dev mock panels, fixture data under `src/`, or `VITE_API_MODE`-style switches. If an earlier mock layer exists on this branch, **delete it entirely**.
- The only fixtures allowed are **small inline test inputs inside `*.test.ts(x)` files** for pure-logic unit tests (adapter, URL validation, `encodeNodeId`, error mapping). They are never imported by app code.
- The only recorded real-response file allowed is the capture-harness graph JSON under `scripts/capture/` (§13), never imported by the app.

---

## 16. Quality and verification
- `npm run typecheck`, `npm run lint`, `npm run check:tokens`, `npm run test` and `npm run build` pass.
- **Unit tests (Vitest):** graph adapter (external exclusion, omitted `file`), `encodeNodeId`, URL validation, `errorCopy` mapping and unknown-code fallback, single-flight refresh and retry-once logic, the admin guard logic (role from `/users/me`), and the verify-email once-only guard.
- **End-to-end (Playwright, against the real running backend):** register → verify (using the backend's dev mail path, or a seeded/bootstrap account per `backend/CLAUDE.md`) → login → submit a real repository → progress → graph → inspect a node → files → relationships → delete → usage updates; the free-limit path if feasible; sign out; and **admin gating**: an `ADMIN` account sees `/admin` and a normal user gets the 404 screen with no Admin link. Skip tests gracefully when the required env vars/backend aren't available, and report that.
- **Hallmark's pre-ship gates:** run and resolve every finding.
- Verify in a real browser at desktop and 390 px widths, in the chosen theme's mode(s), with reduced motion on and off. Report what you checked.

---

## 17. Implementation phases (keep everything green after each)

1. **Discovery, then the theme pause.** Read all docs and the backend source, get the backend running (see `backend/CLAUDE.md`), probe the real endpoints, install/confirm Hallmark, run its theme deal with the §3.3 brief, apply the Blueprint ban, **present three candidates, and STOP.** Nothing visual is built until I choose.
2. **Foundation (after my choice):** scaffold Vite + React + TS + Tailwind + Router + Query, export and wire the tokens, `check:tokens`, `DESIGN-NOTES.md`, Vite proxy, `.env.example`.
3. **API and session core:** `http()`, errors, `types.ts` (verified against the live API), query keys, hooks, token memory, CSRF, boot sequence, single-flight refresh and retry.
4. **Auth screens + shell + guards:** all auth flows (§6.3), OAuth callback, app shell, auth guard, role-aware sidebar, 404.
5. **Dashboard + progress:** submit, validation, history, usage meter and limit state, polling progress view.
6. **Workspace:** Overview, Architecture (adapter, `graphStyles`, layout, controls, inspector, list alternative), Files, Relationships, Ask AI teaser, the three-state locked components, upgrade dialog.
7. **Settings and admin:** settings page, admin guard, `/admin/stats`, `/admin/users`, user detail with status toggle.
8. **Assets and landing:** capture script, generate the PNGs from the real app, assets README, then build the landing page importing them.
9. **Polish and proof:** responsive, accessibility and motion passes, tests, e2e, Hallmark gates, documentation (§19), final report.

---

## 18. Acceptance criteria
- [ ] I was shown **three non-blueprint candidate themes** (each with polarity, typography, surface style) and **explicitly chose one** before any page was built. `Blueprint` and the drafting/grid look appear nowhere: not in the theme, canvas, landing page or images.
- [ ] `DESIGN-NOTES.md` records the choice and the ban. `check:tokens` passes: **no raw hex or font names** outside the token file.
- [ ] There is **no mock code or data** anywhere under `src/` (§15).
- [ ] All data comes from the real `/api/v1` API through the proxy and TanStack Query, matching the real contracts. Errors are handled by `code`.
- [ ] Real auth works: email/password, Google, GitHub, verification, reset, in-memory access token, rotating refresh cookie with CSRF, and automatic single-flight 401 refresh with one retry.
- [ ] The whole loop works against the real backend: sign in → submit a real repo → progress → Overview → graph → inspect → files → relationships. Usage, limits, delete and re-analyze behave per the backend.
- [ ] `/admin/stats`, `/admin/users` and `/admin/users/:userId` work against the real admin API. The Admin link and routes exist **only** for `ADMIN` users per `GET /users/me`. Everyone else gets the standard 404.
- [ ] Locked features follow the three-state rule, decided from the backend's actual capabilities.
- [ ] Every image is a local PNG in `frontend/src/assets/`, imported by static Vite import, with no placeholders or remote URLs. The assets README exists. The capture script is committed.
- [ ] Typecheck, lint, tests, e2e (where runnable) and build are green. Layout works at desktop and 390 px. Reduced motion is respected. Hallmark's gates pass.

---

## 19. Documentation updates
- **`frontend/CLAUDE.md`:** replace "Status: deferred" with the real status (built against the live backend), the structure, the Vite proxy and env setup, the rule that components use only query hooks, the **no-mock policy**, the **Blueprint ban**, the token rule (`check:tokens`), the assets convention (`frontend/src/assets/`, static imports, how to regenerate), and the admin-gating rule.
- **Root `CLAUDE.md`:** one line saying the frontend exists and talks to the real API.
- **`Architecture.md`:** don't change contracts silently. If you find the backend deviates from it, list the differences in your report and propose doc fixes.

---

## 20. Final report (when done)
1. What was built (routes, screens) and how to run it, including the backend setup you used.
2. The theme I chose, its mode(s), and where the tokens live.
3. **Discrepancies** between `Architecture.md` and the real backend, and any backend **gaps** you hit (e.g. missing `role`, no technologies endpoint, history item shape).
4. The state of each locked/coming-soon control (§9) and why.
5. Integration checks from §4: OAuth through the proxy, CSRF cookie readability, email-link paths, production same-origin recommendation.
6. **Every created asset file, its pixel dimensions, and where it appears.**
7. Results of typecheck, lint, `check:tokens`, unit tests, e2e, build, Hallmark gates, and what you verified visually.
8. Anything skipped or decided without being told.