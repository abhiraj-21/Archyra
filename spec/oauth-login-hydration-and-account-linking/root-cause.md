# Root cause: stuck "Opening Archyra" after Google/GitHub login

Written before any code change (Phase 1 of `spec.md`).

## What could and could not be reproduced

I cannot complete a real Google or GitHub consent screen from an automated session. What I reproduced is the
state the browser is in when the provider round trip ends: the backend has set the `refresh_token` cookie
(`Path=/api/v1/auth`), the browser has no `XSRF-TOKEN` cookie, and it makes a **full page load of `/auth/callback`**.
That is exactly what `OAuth2SuccessHandler` redirects to, and the SPA code that runs from there does not depend on
which provider produced the cookie, so Google and GitHub take the same path. A real round trip with each provider
is still on the manual-check list.

Method: a fresh Chromium context, `POST /api/v1/auth/login` through the context's request API (sets the refresh
cookie only, verified: `cookies = [refresh_token[/api/v1/auth]]`), then `page.goto('/auth/callback')`, logging every
`/api/v1` request and response for 8 s. The script is a throwaway probe (not committed); the committed regression
test for this state is `frontend/scripts/e2e/session-boot.spec.ts`.

## Captured trace (dev server, `npm run dev`, StrictMode on, first run)

```
+352ms REQ  GET  /auth/csrf
+363ms RESP 200  /auth/csrf        Set-Cookie: XSRF-TOKEN
+363ms REQ  POST /auth/refresh                         <- refresh #1 (session boot)
+399ms RESP 200  /auth/refresh     Set-Cookie: refresh_token   (rotated)
+409ms REQ  GET  /usage                                <- queries enabled by status=authenticated
+409ms REQ  GET  /users/me
+409ms REQ  POST /auth/refresh                         <- refresh #2 (OAuthCallbackPage -> completeExternalSignIn)
+428ms RESP 200  /auth/refresh     Set-Cookie: refresh_token   (rotated again)
+430ms REQ  GET  /users/me                             <- fetchQuery in completeExternalSignIn
+430ms FAIL /users/me   net::ERR_ABORTED               <- the first /users/me, cancelled
+430ms FAIL /usage      net::ERR_ABORTED
+446ms RESP 200  /users/me
final: URL /dashboard, body text "Opening Archyra"  (still there after 8 s)
```

## Confirmed cause

Two code paths hydrate the session on `/auth/callback`, and the second one destroys the first one's query state:

1. `SessionProvider` boots on page load (`csrf -> refresh`), flips `status` to `authenticated`, and the `/users/me`
   `useQuery` (and, on the dashboard, `/usage`) starts.
2. `OAuthCallbackPage` then calls `completeExternalSignIn`, which runs a **second `POST /auth/refresh`**, then
   `queryClient.clear()`, then `fetchQuery(me)`, then `setStatus('authenticated')`.
3. `queryClient.clear()` removes every query from the cache and aborts the ones in flight (the `ERR_ABORTED` lines).
   The `useQuery` observers in `SessionProvider` are still attached to those removed query objects.
4. `fetchQuery` fills a *new* query with the same key, but `setStatus('authenticated')` sets the value the state
   already has, so React does not re-render and the observers never re-attach to the new query. `meQuery.data`
   stays `undefined` and pending.
5. `RequireAuth` maps that to `meState === 'loading'` and renders `BootScreen` ("Opening Archyra"), which has no
   timer and no way out. A manual refresh works because on a plain page load only the boot runs.

It is a **race**, which is why it looks like "first login only": whether `/users/me` has already finished when
`clear()` runs depends on timing, and a cold first load (uncached lazy chunks, a cold Vite compile, a slow first
request after the cross-site redirect) widens the window. Repeated runs of the same probe:

| Build | Runs | Stuck on "Opening Archyra" |
|---|---|---|
| dev server (StrictMode) | 5 | 1 (the first, cold run) |
| production build (`vite preview`) | 5 | 1 |

So it is **not dev-only**: it reproduced on a production build too, just less often than StrictMode's extra renders
make it in dev.

Experiment that isolates it: with only the `queryClient.clear()` call removed from `completeExternalSignIn` (and
nothing else changed) the probe lands on the dashboard (the second refresh and the aborted calls are still there
in that run, but the page works). That run was reverted; the real fix is below.

A second, independent defect shows in the same trace: the **duplicate refresh**. It did not revoke the login family
in these runs (both calls returned 200) because they ran one after the other, so the second presented the
already-rotated cookie. It is still two rotations per page load, and any overlap would be a replay.

## Hypotheses

| | Hypothesis | Verdict | Evidence |
|---|---|---|---|
| A | Abort plus once-guard under StrictMode leaves boot "booting" | **Ruled out** | Boot completed in every run (`refresh` 200, status became `authenticated`). `bootOnce` is a module-level promise, so StrictMode's second effect re-reads it. |
| B | Duplicate refresh revokes the token family | **Partly confirmed, not the hang** | Two `POST /auth/refresh` per load are real (boot + callback). Both returned 200 because they were sequential, so no reuse detection fired. It is a defect (fixed), but not what spins. |
| C | CSRF mismatch on first login (`403 CSRF_TOKEN_INVALID`) | **Ruled out** | The browser had no `XSRF-TOKEN`; `GET /auth/csrf` ran first and set it, and `refresh` returned 200. (The CSRF refetch-and-retry exists in `postWithCsrf` already; it gets a regression test.) |
| D | State/routing ordering: guard never leaves the boot screen | **Confirmed, as the symptom** | The guard that never leaves is not `BootGate` (boot settled) but `RequireAuth`'s `meState === 'loading'` branch, which renders the same `BootScreen` with no timeout, because the `/users/me` observer was orphaned by `clear()`. |
| E | Proxy or cookie attributes interfere | **Ruled out** | Every call through the Vite proxy returned 200 with the expected `Set-Cookie`; `Path=/api/v1/auth` and `SameSite=Strict` were fine, and the same trace reproduces against the production preview build. |
| F (new) | `queryClient.clear()` racing the mounted `useQuery` observers | **Confirmed: the root cause** | `ERR_ABORTED` on `/users/me` and `/usage` at the instant of the clear, the stuck `Opening Archyra`, and the experiment above. |

## Does it need a backend security-policy change?

No. Cookie attributes, CSRF handling and token rotation are all behaving as designed. The fix is entirely in the
frontend, so I am proceeding without asking.

## Fix chosen

1. **One owner of hydration.** The boot in `hooks/session.tsx` does the whole sequence: `csrf -> refresh ->
   GET /users/me`, and puts the result in the query cache itself, so nothing mounts with a pending `/users/me`.
2. **`/auth/callback` waits, it does not hydrate.** `completeExternalSignIn` is removed. The callback page reads
   the session status (the boot gate already holds the page until boot settles): authenticated goes to the stored
   return target, anonymous goes to `/login?error=oauth_failed`.
3. **No `queryClient.clear()` over mounted observers.** Sign-in seeds the new user's `me` after clearing instead
   of relying on a refetch.
4. **Exactly one refresh per page load**: a module-level single-flight boot promise, independent of component
   mounts, effect cleanup and abort signals.
5. **A hard 10 s boot timeout** (`BOOT_TIMEOUT_MS`) that aborts the in-flight auth requests, and a retry screen
   ("Try again", "Back to sign in"). Aborting matters: abandoning a hung refresh that later lands would rotate
   the cookie under the retry's feet.
6. CSRF `403 CSRF_TOKEN_INVALID` on refresh/logout keeps its one refetch plus one retry; a 401 is never retried.
