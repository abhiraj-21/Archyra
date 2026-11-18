# frontend/CLAUDE.md

Frontend-specific guidance. System-wide architecture, API standards and the roadmap are in the root `CLAUDE.md` and `Architecture.md`. The build spec is `spec/ui/spec-ui.md`; design decisions are in `DESIGN-NOTES.md`.

## Status

Built against the live backend (V0.1 and V0.5 APIs): landing, auth (email/password, Google, GitHub, verification, password reset), dashboard with history and usage, the polling progress view, the repository workspace (Overview, Architecture, Files, Relationships, Ask AI teaser), settings, and the admin area. Ask AI and the V2/V3 graph features are shown as "coming soon" and never called.

## Commands

```bash
npm run dev             # Vite on :5173, proxying /api, /oauth2, /login/oauth2 to the backend
npm run verify          # typecheck + lint + check:tokens + unit tests + build + session-boot e2e (keep it green; the e2e part skips without the running stack)
npm run e2e:session     # just the session-boot regression tests (scripts/e2e/session-boot.spec.ts)
npm run e2e             # Playwright against the running app and backend (skips without .env.local creds)
npm run assets:capture  # regenerate src/assets/*.png from the running app (see src/assets/README.md)
node scripts/e2e/probe-api.mjs   # print live response SHAPES (no values) to check src/api/types.ts

npx vitest run src/lib/url.test.ts          # one unit test file
npx vitest run -t "encodeNodeId"            # unit tests by describe or test name
npm run e2e -- -g "admin gating"            # e2e tests by name
```

## Setup

- `VITE_BACKEND_ORIGIN` (default `http://localhost:8080`) is the proxy target; `.env.example` lists every variable. `VITE_*` values ship to the browser: never a secret there.
- The browser only uses relative URLs, so SPA and API share an origin and the `SameSite=Strict` refresh cookie works. The proxy keeps the original `Host` (`changeOrigin: false`) and adds `X-Forwarded-*` (`xfwd`), so the backend builds OAuth redirect URIs on `http://localhost:5173`. Those URIs must be registered with Google and GitHub. In production, serve the SPA and the API on one origin behind a reverse proxy.
- Capture and e2e scripts read test accounts from an untracked `.env.local` (`ARCHYRA_TEST_*`, `ARCHYRA_ADMIN_*`). Use dedicated test accounts, never a real person's.
- The register → verify-email e2e test is skipped because mail goes to a real SMTP relay. Pointing the backend at the local MailHog sink described in `backend/README.md` gives a readable dev mailbox.
- `assets:capture` and the analysis e2e test analyse a repository as the test user: each run briefly uses one free analysis (returned on delete or when a re-run replaces the old one).

## Structure

```
src/
  api/         transport.ts (fetch), http.ts (401 → single-flight refresh → retry once), auth.ts (CSRF, refresh, logout),
               session.ts (in-memory access token), errors.ts, types.ts, queryKeys.ts, endpoints/*.ts
  hooks/       TanStack Query hooks per endpoint, session provider and context, upgrade context
  components/  ui/ (buttons, fields, dialog, chips, locked states), layout/ (AppShell), graph/ (adapter, graphStyles,
               layout worker with dagre, canvas, inspector, legend)
  features/    landing, auth, dashboard, analysis-progress, repository/{overview,architecture,files,relationships,ask-ai},
               usage, upgrade, settings, admin, system (boot, 404)
  routes/      router.tsx, guards.tsx
  lib/         url.ts (repo URL check), encode.ts (node ids in paths), errorCopy.ts, returnTo.ts, once.ts, plans.ts
  styles/      tokens.css (THE token file), base.css, components.css, index.css (Tailwind + token mapping)
  assets/      captured PNGs + README.md
scripts/       check-tokens.mjs, capture/, e2e/, lib/env.mjs (never imported by the app)
```

## How the pieces fit

- **Session boot** (`hooks/session.tsx`) is the **only** code that hydrates a session on page load: `GET /auth/csrf` → `POST /auth/refresh` (HttpOnly cookie) → `GET /users/me` (seeded into the query cache), as one module-level single-flight promise, so StrictMode's double effects and remounts share it: exactly one `POST /auth/refresh` per page load. It has a hard `BOOT_TIMEOUT_MS` (10 s) that aborts the in-flight auth requests (`abortAuthRequests`) and shows the retry screen (`BootScreen failed`: *Try again* re-runs the boot, *Back to sign in* calls `abandonBoot`); a network failure does the same. `routes/guards.tsx` shows the boot screen until it settles. `/auth/callback` (Google/GitHub return) **never calls refresh**: it waits behind `BootGate`, then goes to the stored return target (`lib/returnTo.ts`) when authenticated, or to `/login?error=oauth_failed` when the boot found no session. Never `queryClient.clear()` and then rely on a refetch while `useQuery` observers are mounted (the original "Opening Archyra" hang: `spec/oauth-login-hydration-and-account-linking/root-cause.md`); clear, then `setQueryData` the new `me`. `api/http.ts` turns a `401 AUTHENTICATION_REQUIRED` into one shared refresh and one retry; `refresh`/`logout` refetch the CSRF token and retry once on `403 CSRF_TOKEN_INVALID` (never on 401). A failed refresh calls `expireSession`, and the guard redirects to `/login?next=…` (a deliberate sign-out goes to plain `/login`).
- **Analysis lifecycle:** `/analyses/:id` polls status (`useAnalysisStatus`, 2 s, stops at COMPLETED/FAILED) and hands over to `/analyses/:id/overview`; the workspace layout redirects back to the progress view while the analysis is not COMPLETED. Result queries take a `ready` flag and stay idle until then.
- **Graph:** the whole graph is fetched once per analysis (`useGraph`, `staleTime: Infinity`) and filtered client-side (the V0.1 `nodeType`/`relationshipType` take one value each). `graph/adapter.ts` produces the visible set, `graph/useLayout.ts` runs dagre in `layout.worker.ts` (cached by visible ids), `GraphCanvas` renders it and fits the connected part. Filters, the selected node and the list view live in the URL (`?nodes=&edges=&node=&view=`).

## Rules

- **Components use only hooks** from `src/hooks/`. They never call `fetch` or import `api/http`, `api/endpoints/*` or `api/session` (ESLint enforces it). Every query key lives in `api/queryKeys.ts`; the graph key carries every `/graph` parameter, present or not.
- **No mocks, ever.** No mock client, fixture data, MSW, simulators, dev mock panels or `VITE_API_MODE` switches in `src/`. The only allowed inputs are small inline values inside `*.test.ts(x)` for pure logic. Types are hand-written from the backend DTOs and confirmed with `probe-api.mjs`; if the backend lacks something, report it instead of inventing data.
- **Session:** the access token lives in module memory only (never storage, URLs or logs). Refresh and logout send `X-XSRF-TOKEN` from `GET /auth/csrf`. Refresh is single-flight: two parallel refreshes would replay the rotating cookie and revoke the login family.
- **Errors** are handled by `code` through `lib/errorCopy.ts`, never by message text. API strings render as text only; links only from validated values.
- **Admin gating:** the Admin link and every `/admin/**` route exist only when `GET /users/me` returns role `ADMIN`. Everyone else, anonymous visitors included, gets the ordinary 404 screen. A `403 ACCESS_DENIED` from an admin call refetches `/users/me`. The backend enforces access regardless.
- **Locked features, three states:** available; PRO-locked (`ProLocked`, opens the upgrade dialog; also on `403 PREMIUM_FEATURE`); coming soon (`ComingSoon`, never calls the backend). Decide by reading the backend. Today nothing is PRO-locked: the premium graph parameters exist only as a gate and return the same graph.

## Design

- Theme: Hallmark **Coral**, light only. **`Blueprint` and blueprint/engineering-grid aesthetics are banned**: no cyan or blue drafting paper, no grid, cross or dot pattern on any page, canvas or image (never render React Flow's `<Background>`), no drafting motifs.
- **Tokens:** every colour and font is a token in `src/styles/tokens.css`; Tailwind reads them through `@theme inline reference` in `index.css`, with the stock palette removed. `npm run check:tokens` fails on any hex, `rgb()`/`hsl()`/`oklch()`, font-family literal, font name or stock Tailwind colour class elsewhere in `src/`.
- Graph node and edge styles are defined once in `components/graph/graphStyles.ts`, keyed by type; the legend, minimap and canvas read from it. Colour is never the only signal (glyph shapes, dash patterns, markers).
- CSS files carry the Hallmark stamp in their first lines. Run `node ~/.claude/skills/hallmark/scripts/sloplint.mjs src --scope app` (and the landing with `--url http://localhost:5173/ --render`) before shipping UI changes.

## Assets

Images live in `src/assets/` as PNGs produced by `npm run assets:capture` from the real app (Chromium, 2x, a real spring-petclinic analysis by a test account; never admin screens, settings, menus or emails). Import them with static Vite imports; replacing an image means overwriting the file with the same name. Containers size images with `aspect-ratio` and `object-fit`, so new dimensions do not break layouts. `src/assets/README.md` lists every file.
