# Spec: Post-OAuth "Opening Archyra" hang fix + Account linking in Settings

Location: `spec/oauth-login-hydration-and-account-linking/spec.md` (repo root).
Builds on: `Architecture.md`, root `CLAUDE.md`, `frontend/CLAUDE.md`, `backend/CLAUDE.md`, and `spec/graph-controls-and-auth-fixes/spec.md` (do not edit or regress that spec: the `http://localhost:5173/login/oauth2/code/{google,github}` redirect URIs, the generic 202 on duplicate register, and the existing OAuth login error codes stay as they are).

Two tasks, one spec. Do Task 1 first. Task 2's return trip from the provider lands on a full page load that depends on Task 1's session boot working.

Conventions that apply throughout: package-by-feature under `com.abhiraj.archyra`; TanStack Query hooks only on the frontend; Hallmark "Coral" tokens only (`npm run check:tokens` must pass); no mocks anywhere; existing error envelope and error-code style; `application.properties`, not YAML.

---

## Task 1: Fix the stuck "Opening Archyra" screen after Google/GitHub login

### Symptom (reported)
After Google login the browser lands on `/dashboard` and shows "Opening Archyra" indefinitely. A manual refresh loads the dashboard.
- Seen under `npm run dev` (Vite, React StrictMode on).
- Email/password login and registration work.
- Happens only on the first login after signing out or clearing cookies.
- Only Google was tried. GitHub is untested, so reproduce with both.
- DevTools was not inspected, so there is no network evidence yet.

### Phase 1: Reproduce and prove the root cause (before any fix)
1. Reproduce with Google and with GitHub, in a clean state: sign out, then clear all site data for both `localhost:5173` and `localhost:8080`, then log in. Also try the "already signed in earlier, not first login" path to see whether it differs.
2. Capture the network trace for the whole flow, from `/oauth2/authorization/{provider}` through `/login/oauth2/code/{provider}`, the redirect to `/auth/callback`, then `GET /auth/csrf`, `POST /auth/refresh`, `GET /users/me`. Record for each: status, order, count, whether it is pending, and `Set-Cookie` headers (refresh cookie rotation, `XSRF-TOKEN`).
3. Compare dev (StrictMode on) against a production build (`npm run build` + preview). State whether the bug is dev-only.
4. Evaluate these hypotheses explicitly, each marked confirmed or ruled out with evidence:
    - A. **Abort plus once-guard under StrictMode.** The boot effect aborts or ignores its first run on cleanup while the `lib/once.ts` guard blocks the second run, so the boot never completes and the state stays "booting".
    - B. **Duplicate refresh.** Boot and `/auth/callback` (or StrictMode's double effect) both call `POST /auth/refresh`. The rotating cookie then makes the second call a reuse, which revokes the whole token family.
    - C. **CSRF mismatch on the first login.** The login on the handshake chain regenerates the CSRF token, so the `XSRF-TOKEN` cookie or the cached header is stale and `refresh` returns 403 `CSRF_TOKEN_INVALID`, which boot does not handle.
    - D. **State or routing ordering.** Boot finishes (or is skipped) before the router or guard is listening, so the guard never leaves the boot screen. Check how `routes/guards.tsx` and `/auth/callback` interact with the boot state.
    - E. **Proxy or cookie behavior.** The Vite proxy (`changeOrigin:false`, `xfwd`) or cookie attributes (`Path=/api/v1/auth`, `SameSite=Strict`) interfering with the first request after the cross-site redirect.
5. Write the findings to `spec/oauth-login-hydration-and-account-linking/root-cause.md`: reproduction steps, the captured traces (condensed), the confirmed cause, the ruled-out hypotheses, and the fix chosen. Then proceed to Phase 2. Stop and ask the user only if the root cause turns out to need a backend security-policy change (for example, changing cookie attributes or CSRF handling).

### Phase 2: Fix (frontend; backend only if Phase 1 proves it necessary)
The final design must satisfy all of these, whatever the confirmed cause was:

1. **One owner for session hydration.** The session boot in `hooks/session.tsx` (csrf → refresh → `/users/me`) is the only code that hydrates a session on page load. `/auth/callback` must not call refresh itself. It waits for the boot result, then navigates to the stored return target (`lib/returnTo.ts`) or `/dashboard`. Remove any second refresh path.
2. **Exactly one `POST /auth/refresh` per page load**, never in parallel, including under StrictMode's double effects. The once-guard must be a module-level single-flight promise (not tied to component mount or an effect cleanup or abort signal), so a remounted component re-reads the same in-flight result instead of starting over or being skipped.
3. **CSRF handling.** If `refresh` or `logout` gets 403 `CSRF_TOKEN_INVALID`, refetch `GET /auth/csrf` and retry that request once. This is safe because the request was rejected before any rotation. Never retry a refresh that got 401.
4. **Boot can never spin forever.** A hard timeout of about 10 seconds on the whole boot sequence, as a named constant. On timeout or a network failure, the boot screen is replaced by a retry screen with two actions: "Try again" (re-runs the boot as a fresh single-flight) and "Back to sign in". Keep the Hallmark look, with no new colors outside tokens.
5. **Outcome mapping.**
    - A 401 on the first boot of an anonymous visitor is normal: unauthenticated, show the public pages or login.
    - A 401 or failure while on `/auth/callback` means the social login did not produce a session: go to `/login?error=oauth_failed`.
    - Timeout or network error anywhere: the retry screen from item 4.
6. The boot screen must clear in every outcome. No state may leave the guard in "booting" without a timer running.
7. Verify the fix for both Google and GitHub, in dev and in a production build.

### Regression tests (Playwright, `npm run verify` must include them)
Google and GitHub cannot be automated, so reproduce the post-redirect state directly:
- **Callback hydration:** log in through the API (using the existing e2e login helper) so the browser context holds the refresh cookie, make sure no `XSRF-TOKEN` cookie is present, then load `/auth/callback` fresh. Assert the dashboard renders, the boot screen is gone within a few seconds, and exactly one `POST /api/v1/auth/refresh` was made. Runs against the dev server (StrictMode on).
- **Hang guard:** make `/auth/refresh` never respond (route interception), advance the page clock past the timeout (`page.clock`, no real 10-second wait), and assert the retry screen appears and "Try again" works once the route is released.
- **Failed callback:** make `/auth/refresh` return 401 while on `/auth/callback` and assert the redirect to `/login?error=oauth_failed`.
- **CSRF retry:** make the first `refresh` return 403 `CSRF_TOKEN_INVALID` and assert one csrf refetch plus one retry, ending on the dashboard.
  Interception is allowed only to inject network faults in tests, never to fake data.

---

## Task 2: Account linking in Settings

### Decisions (from the interview)
- **Email mismatch:** allowed. Linking is proven by the signed-in user completing the provider login. The provider's email is stored on the identity row and never changes the account email. The provider email is not required to be verified, and is never used for auto-linking by login (the existing login resolver is unchanged).
- **Identity already linked to a different account:** block, change nothing, fixed error code, no disclosure of who owns it. Account merging is out of scope.
- **One identity per provider per user.** A user who already has a Google identity cannot link a second, different Google account.
- **Re-authentication:** a valid access token is enough to start a link (plus the anti-CSRF binding below).
- **Unlinking is in scope**, but it must never remove the last way to sign in.
- **Notification emails** on both link and unlink, through the existing `mail/` service.
- **Set password** for OAuth-only users is out of scope. Those users use Forgot password. Verify that the existing reset flow really works for an account with no password. If it does not, report it to the user; do not silently add a feature.
- Link and unlink never touch the refresh cookie or refresh-token families.

### Backend

**Endpoints** (all bearer-authenticated on the stateless API chain, following existing conventions for CSRF, error envelope and rate limiting):

1. `GET /api/v1/users/me/linked-identities` returns
   `{ "hasPassword": boolean, "identities": [ { "provider": "google"|"github", "email": string|null, "login": string|null, "linkedAt": string|null } ] }`
   Additive. Leave `GET /users/me` and its `linkedProviders` field unchanged.
2. `POST /api/v1/users/me/linked-identities/{provider}/link-intent` creates a link intent and returns `{ "authorizationUrl": "/oauth2/authorization/{provider}?link_intent=<token>" }`. It also sets the binding cookie described below. Unknown provider: 400 `VALIDATION_ERROR`. If the user already has that provider linked: 409 `PROVIDER_ALREADY_LINKED`. Rate limit with the existing `RATE_LIMITED` mechanism.
3. `DELETE /api/v1/users/me/linked-identities/{provider}` unlinks. 404 `IDENTITY_NOT_FOUND` if not linked. 409 `LAST_LOGIN_METHOD` if, after removal, the account would have neither a password nor any other linked identity. Take a pessimistic lock on the user row so two concurrent unlinks cannot both pass the guard. Send the unlink notification email after commit.

`hasPassword` must reflect a real password. Check how OAuth-created users store their password hash (null versus a placeholder) and base `hasPassword` and the guard on whatever is a reliable indicator. If no reliable indicator exists, add one (see Database) rather than guessing.

**Link flow on the handshake chain** (`/oauth2/**`, session IF_REQUIRED):

- **Intent.** A random 256-bit token, stored hashed, 5-minute TTL, single use, bound to the user id and provider, plus a second random secret (also stored hashed) used for browser binding.
- **Browser binding (required).** The link-intent response sets an `HttpOnly`, `SameSite=Strict`, `Path=/oauth2/authorization`, `Max-Age=300` cookie carrying the binding secret. When the authorization request arrives with `link_intent`, a filter before the OAuth2 authorization-redirect filter must require both a valid unexpired unconsumed token and a matching binding cookie. This stops an attacker from sending a victim a crafted `link_intent` URL that attaches the victim's Google/GitHub identity to the attacker's account. Failure: redirect to `{frontend-base-url}/settings?link_error=link_expired`.
- **Consume at authorization time.** On success, mark the intent consumed and copy `{userId, provider, expiresAt}` into the handshake session, then clear the binding cookie. The token in the URL is dead from that moment.
- **Stale state.** A normal login request (no `link_intent`) must clear any link attributes left in the session, so a later plain login can never be treated as a link.
- **Success handler.** If the session holds link context and its provider matches the callback registration:
    - Do not log anyone in. Do not issue tokens or cookies.
    - Resolve the user strictly from the session-bound `userId`, never from the provider email. Reject if the user is missing or disabled (`oauth_account_disabled`).
    - If `(provider, provider_user_id)` is already linked to the same user: treat as success (idempotent).
    - If linked to another user: error `identity_already_linked`, no change.
    - If this user already has a different identity for this provider: error `provider_already_linked`.
    - Otherwise create the `linked_identities` row with `provider_email`, `provider_login` (GitHub username, null for Google) and `created_at`, send the link notification email after commit (an email failure is logged and must not fail the link), invalidate the handshake session, and redirect to `{frontend-base-url}/settings?link=ok&provider=<provider>`.
    - Enforce the 5-minute expiry again here using the session-stored `expiresAt`.
- **Failure handler.** In link mode, failures redirect to `{frontend-base-url}/settings?link_error=<code>` instead of `/login`. Codes: `oauth_cancelled`, `oauth_failed`, `oauth_account_disabled`, `identity_already_linked`, `provider_already_linked`, `link_expired`. Clear the link context in every outcome.
- The existing registered redirect URIs and `{frontend-base-url}` property are reused. No new provider-side configuration is needed.

**Notification emails.** Link and unlink each send a short notice to the account's address: the provider name, the time, and a "wasn't you? reset your password" link to the existing forgot-password page. Reuse the mail templates and sending conventions in `mail/`. Do not include tokens in these emails.

**New API error codes** (add to the existing set and the frontend error map): `PROVIDER_ALREADY_LINKED` (409), `LAST_LOGIN_METHOD` (409), `IDENTITY_NOT_FOUND` (404). The `link_error` and `link` query values above are redirect codes, not API error codes, and stay a fixed allow-list on the frontend.

### Database / schema changes
`ddl-auto=update` is in use, so new columns must be nullable. Inspect the `LinkedIdentity` entity first and add only what is missing.
- `linked_identities`: add nullable `provider_email` (varchar), `provider_login` (varchar), `created_at` (timestamp). Existing rows keep null values, which the UI shows as "—". Do not backfill. Keep the existing unique `(provider, provider_user_id)`. Add a unique `(user_id, provider)`, after checking that existing data has no duplicates.
- New table `oauth_link_intents`: `id`, `token_hash` (unique), `binding_hash`, `user_id` (FK to users), `provider`, `created_at`, `expires_at`, `consumed_at` (nullable). Purge expired and consumed rows lazily on use and with the existing cleanup job if there is one (otherwise a small scheduled job).
- Only if Task 2 finds no reliable password indicator: add a `password_set` boolean on users, set when a real password is stored and cleared whenever the existing logic clears it.

### Frontend
- Settings page gets a "Sign-in methods" section with one row per provider (Google, GitHub). Each row shows: provider icon, name, a Linked / Not linked badge, the linked email (GitHub: the login handle), the linked date (or "—" when missing), and an action button.
- Not linked: **Link**. It calls the link-intent mutation, shows a loading state, then `window.location.assign(authorizationUrl)`. Map `RATE_LIMITED` and `PROVIDER_ALREADY_LINKED` to inline errors.
- Linked: **Unlink**, with a confirmation dialog (reuse the existing dialog component). Disabled with an explanation when it is the only way to sign in (driven by `hasPassword` and the identity count: "Set a password or link another provider first", with a pointer to Forgot password for OAuth-only users). Handle a server-side `LAST_LOGIN_METHOD` too.
- Return handling: on `/settings`, read `?link=ok&provider=...` or `?link_error=<code>`. Success shows a toast and the row updates. Errors show a dismissible inline banner at the top of the section, with fixed copy per code and a generic fallback for unknown values. Remove the params from the URL (replace navigation) after reading, and invalidate the identities query.
- Hooks: `useLinkedIdentities`, `useStartLink`, `useUnlinkIdentity` in the existing TanStack Query structure. No ad-hoc fetches.
- The return from the provider is a full page load, so it goes through the session boot fixed in Task 1. Do not add any special session handling for it.
- If the user's session ended while they were at the provider, the boot sends them to login as usual. The link itself already completed (the intent was bound at the start).
- Hallmark Coral tokens only. Keep it accessible: real buttons, badge text (not color alone), focus-visible, labelled dialog.

### Tests
- **Backend integration** (Google/GitHub cannot be automated, so drive the handler with fabricated OAuth2 principals and a prepared session):
    - intent: token single use, expiry, wrong provider, missing or wrong binding cookie, token bound to the right user
    - handler: link success, idempotent re-link, identity linked to another user, second identity for the same provider, disabled user, email mismatch allowed, no tokens or cookies issued, session cleared, stale link context cleared on a normal login
    - `GET linked-identities`: shape and `hasPassword`
    - unlink: success, not linked, last-method guard (password only, provider only, both), concurrent unlink race
    - emails: sent after commit on link and unlink; a mail failure does not fail the operation
    - `GET /users/me` unchanged
- **Playwright:** log in through the API, open Settings, assert both providers show Not linked with a Link button; click Link and assert the browser navigates to `/oauth2/authorization/<provider>?link_intent=...` (intercept and abort the navigation at the provider boundary); assert the return-handling UI by loading `/settings?link=ok&provider=google` (toast, params removed) and `/settings?link_error=identity_already_linked` (banner, params removed). Unlink UI needs a linked identity: use an existing e2e seeding mechanism if the repo has one. Do not add a test-only endpoint to production code. If there is none, cover unlink in the backend tests and list it as manual verification.

---

## Acceptance criteria

Task 1
1. Google and GitHub login (first login after clearing cookies, dev server, StrictMode on) land on the dashboard with no manual refresh. Same on a production build.
2. `root-cause.md` exists with reproduction steps, trace evidence, the confirmed cause, and every hypothesis A to E marked confirmed or ruled out.
3. Exactly one `POST /auth/refresh` per page load, including in dev with StrictMode.
4. `/auth/callback` never calls refresh itself.
5. A hung boot shows the retry screen after about 10 seconds; "Try again" and "Back to sign in" work. The boot screen cannot spin indefinitely in any outcome.
6. A 403 `CSRF_TOKEN_INVALID` on refresh causes exactly one csrf refetch and one retry; a 401 is never retried.
7. All four Playwright regression tests pass and are part of `npm run verify`.

Task 2
8. Settings shows Google and GitHub rows with correct linked state, email or handle, and date.
9. An email/password user can link Google and GitHub; afterwards they can sign in with either and reach the same account.
10. Linking a provider whose email differs from the account email succeeds, and the account email is unchanged.
11. Linking an identity already linked to another account is blocked with `identity_already_linked`, with no data changed and no owner disclosed.
12. Linking a second different account of the same provider is blocked with `provider_already_linked`.
13. A link intent works once, expires after 5 minutes, and fails without the matching binding cookie (verified by a test that replays the URL without the cookie).
14. A link never issues tokens or cookies and never alters the refresh cookie or token families. A plain login after any link attempt behaves as a normal login.
15. Unlink works when another sign-in method remains, is blocked with `LAST_LOGIN_METHOD` otherwise (UI and API), and concurrent unlinks cannot leave the account with no way to sign in.
16. Link and unlink each send one notification email; a mail failure does not fail the operation.
17. Success shows a toast and updated rows; errors show the inline banner; query params are removed after reading.
18. No existing behavior regresses: login resolver matching and auto-link by verified email, duplicate-register 202 flow, the redirect URIs, `GET /users/me` shape.
19. `npm run verify` (including `check:tokens` and Playwright) and the backend test suite pass.

## Order of work
1. Read the docs and existing spec; read the current session boot, guards, callback route, OAuth handlers and the `LinkedIdentity` entity.
2. Task 1 Phase 1 (reproduce, `root-cause.md`), then Phase 2 (fix and tests). Commit separately.
3. Task 2 backend (schema, intent filter, handlers, endpoints, emails, tests). Commit separately.
4. Task 2 frontend (Settings section, hooks, return handling, tests). Commit separately.
5. Run everything, then write a short summary of what changed and anything that needs a manual check.