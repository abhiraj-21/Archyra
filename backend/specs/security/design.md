# Security design (as built)

How the security described in `spec.md` actually works in the code. `spec.md` is the requirements document; this file
records what was built, where, and the places where the implementation deliberately differs. For setup and
environment variables see `../../README.md`.

## Contents

1. [Two filter chains](#1-two-filter-chains)
2. [Access tokens](#2-access-tokens)
3. [Refresh tokens, cookie and CSRF](#3-refresh-tokens-cookie-and-csrf)
4. [Passwords and login](#4-passwords-and-login)
5. [Email verification and password reset](#5-email-verification-and-password-reset)
6. [Social login (Google, GitHub)](#6-social-login-google-github)
7. [Ownership](#7-ownership)
8. [Premium](#8-premium)
9. [Hardening](#9-hardening)
10. [Admin and profile](#10-admin-and-profile)
11. [Testing the security code](#11-testing-the-security-code)
12. [Deviations from spec.md](#12-deviations-from-specmd)
13. [Not built](#13-not-built)

## 1. Two filter chains

- **API chain** (`security/SecurityConfig`, `@Order(2)`): stateless, OAuth2 resource server, CORS, headers (HSTS, no
  referrer, CSP `default-src 'none'`), `@EnableMethodSecurity`. Rules, first match wins:

  | Path | Rule |
  | --- | --- |
  | `/api/v1/auth/me` | authenticated |
  | `/api/v1/auth/**` | public (refresh and logout rely on cookie + CSRF instead) |
  | `/api/v1/admin/**` | ROLE_ADMIN |
  | `/error` | public |
  | `/api/v1/**` | authenticated |
  | anything else | denied |

- **OAuth2 login chain** (`auth/oauth2/OAuth2LoginSecurityConfig`, `@Order(1)`): matches only `/oauth2/**` and
  `/login/oauth2/**`. See section 6.
- 401 and 403 bodies are the normal `{ code, message }`, written by `RestAuthenticationEntryPoint` (`AUTHENTICATION_REQUIRED`,
  with `WWW-Authenticate: Bearer`, plus `error="invalid_token"` when a token was sent) and `RestAccessDeniedHandler`
  (`ACCESS_DENIED`, or `CSRF_TOKEN_INVALID`). They never say why a token was rejected.
- **Encoded path ids:** node and file ids contain `/`, `#` and spaces and arrive percent-encoded. `common/TomcatConfig` sets
  Tomcat's `encodedSolidusHandling=passthrough`, and `SecurityConfig.httpFirewall()` allows the encoded slash and the
  encoded percent that results. Everything else the strict firewall blocks stays blocked. Do not tighten it without running
  `NodeEndpointHttpTest` and `FileAndClassEndpointHttpTest` (real HTTP; MockMvc would not notice).

## 2. Access tokens

- Only `security/JwtService` creates them: HS256, 15 minutes, claims `iss`, `sub` (user id, never the email), `iat`, `exp`,
  `jti`, `roles`. **The plan is never a claim**, so a plan change applies immediately.
- `JwtConfig` builds the encoder and decoder on Spring Security's Nimbus support (no extra JWT library). The decoder checks
  signature and algorithm, expiry (30 s skew), issuer, and rejects tokens without `exp`. `roles` becomes `ROLE_<role>` authorities.
- Read `iss` with `jwt.getClaimAsString("iss")`: the issuer is a plain word, not a URL, so `getIssuer()` throws.
- Spring Security 7 adds a `FACTOR_BEARER` authority to bearer authentications; do not assert "exactly these authorities".
- `SecurityProperties` (`app.security.*`) fails startup if the decoded secret is under 32 bytes, CORS origins contain a
  wildcard, or (with the `prod` profile) the cookie is not `Secure` or the frontend URL is not https.

## 3. Refresh tokens, cookie and CSRF

- A refresh token is 32 random bytes (base64url); only its SHA-256 hash is stored (`refresh_tokens`). The cookie is
  `HttpOnly; SameSite=Strict; Path=/api/v1/auth; Max-Age=7d` (`Secure` unless `COOKIE_SECURE=false`). SameSite=Strict means the SPA
  and API must share a registrable domain.
- `RefreshTokenService.rotate` locks the row, revokes the presented token and issues a successor in the same family. Presenting an
  already-revoked token revokes the whole family and writes a `REFRESH_TOKEN_REUSE` audit line. Each rotation is a fresh 7 days
  (sliding). Two simultaneous refreshes from one browser look like theft, so the SPA must serialise them.
- Logout revokes the family; password reset, a disabled account and a social-login takeover revoke every token of the user.
- **CSRF** applies only to `POST /auth/refresh` and `/auth/logout` (they authenticate by cookie). `GET /auth/csrf` returns
  `{headerName, token}` and sets the readable `XSRF-TOKEN` cookie (`Path=/`, SameSite=Strict); the client echoes the token in
  `X-XSRF-TOKEN`. The **plain** token handler is used on purpose: `csrf.spa()` writes the cookie on every response, including bearer calls.

## 4. Passwords and login

- Delegating encoder (bcrypt). bcrypt in Spring Security 7 refuses more than 72 bytes, so `PasswordPolicy` allows 8 to 72 bytes,
  not equal to the email, not in a short common list. Login treats a password over 72 bytes as an ordinary bad credential.
- `AuthService.login` order: rate limit (filter), lockout, credentials, enabled, email verified. A wrong password, an unknown email,
  a locked account and a social-only account all return the same `401 INVALID_CREDENTIALS`. `ACCOUNT_DISABLED` and
  `EMAIL_NOT_VERIFIED` (both 403) are returned only after the password was right. `AppUserDetailsService` always reports
  `enabled=true` for exactly that reason, and gives passwordless accounts an unusable hash so the timing is the same.
- 5 failures lock the account for 15 minutes (`User.recordFailedLogin`), never mentioned to the client.
- Register hashes the password even when the email already exists and answers 202 with an identical body.

## 5. Email verification and password reset

- `AccountEmailFlows` + `OneTimeTokenService`: single-use hashed tokens (24 h verification, 30 min reset); issuing a new one voids earlier
  unused ones; consumption locks the row.
- `resend-verification` and `forgot-password` always answer 202 with the same body and mail only enabled accounts (verification: only unverified
  ones). Social-only accounts can add a password through forgot-password.
- `reset-password` runs in one transaction: a rejected new password leaves the link usable; success sets the hash, clears the lockout, revokes all
  sessions, and does not log in. It does not mark an unverified email as verified.
- `mail/SmtpEmailService` sends plain text on the bounded `mailExecutor` (a full queue drops the mail), never throws, never logs the address or link.
  Always use `@Async("mailExecutor")`-style qualified executors: there are two `Executor` beans.

## 6. Social login (Google, GitHub)

- The handshake chain is the only place with an HTTP session, kept just for the authorization request (state, nonce). The signed-in principal is not
  stored in it (`RequestAttributeSecurityContextRepository`), and both handlers destroy the session and delete `JSESSIONID`.
- `SocialOidcUserService` (Google) and `SocialOAuth2UserService` (GitHub) turn provider data into a `ProviderIdentity` and call `SocialAccountResolver`
  (one transaction):
  1. the provider email must be verified (Google `email_verified`; GitHub: the primary **and** verified entry of `/user/emails`, never the profile email);
  2. a link on `(provider, providerUserId)` wins, even if the email changed;
  3. otherwise an account with the same email is linked; if that account's email was unverified, it is taken over: password removed, email marked
     verified, all sessions revoked. An account already linked to a *different* identity of the same provider is refused (`oauth_failed`);
  4. otherwise a verified `USER`/`FREE` account without a password is created. Disabled accounts never sign in.
- `OAuth2LoginSuccessHandler` starts a refresh family, sets the cookie and redirects to `frontend-base-url + success-redirect-path`. The SPA then calls
  `/auth/csrf` and `/auth/refresh`, so the access token comes from the same code as a password login and no secret is ever in a URL. Redirect targets
  come only from configuration. `OAuth2LoginFailureHandler` redirects to `...?error=` with `oauth_failed`, `oauth_email_unverified`,
  `oauth_account_disabled` or `oauth_cancelled` (a closed set).
- Services with two constructors (a test-only one that lets the delegate be replaced) must mark the injection constructor `@Autowired`.

### Linking from Settings (link mode)

Adding a provider to a signed-in account is a **different handshake on the same chain**, switched on by a context in the handshake session, never by
the provider's email. The resolver above is unchanged and is **not called** in link mode.

- **Intent.** `POST /api/v1/users/me/linked-identities/{provider}/link-intent` (bearer, rate limited per user) stores `oauth_link_intents`: a random
  256-bit token (in the URL) and a second random 256-bit secret (in the `link_binding` cookie: `HttpOnly`, `SameSite=Strict`,
  `Path=/oauth2/authorization`, 5 minutes), both stored only as SHA-256 hashes, bound to the user and provider, single use.
- **Why the cookie.** The authorization-start URL is a plain `GET`. Without a second secret that only the asking browser holds, an attacker could mint
  an intent for their own account and send the URL to a victim; the victim, already signed in at Google, would complete the handshake and attach the
  **victim's** identity to the **attacker's** account (an account takeover through "Sign in with Google"). `LinkAuthorizationFilter` therefore requires
  a valid token **and** the matching cookie, and consumes the intent only then (replaying the URL without the cookie neither works nor burns it).
- **Callback.** The context `{userId, provider, expiresAt}` lives only in the handshake session; a plain login request clears it, and every outcome
  destroys the session. `OAuth2LinkHandler` takes the account from the session (never from the provider's email), links, issues **no** tokens or
  cookies, and redirects to `/settings?link=ok&provider=` or `/settings?link_error=` (closed set: `oauth_cancelled`, `oauth_failed`,
  `oauth_account_disabled`, `identity_already_linked`, `provider_already_linked`, `link_expired`). An identity already linked to another account is
  refused without naming it; one identity per provider per user (unique `(user_id, provider)`).
- **Unlink** never removes the last way to sign in (`LAST_LOGIN_METHOD`); link and unlink lock the user row so concurrent requests are serialized.
  Both send a notice after commit (no token); a mail failure never fails them. Neither touches refresh tokens.
- Provider emails are not required to be verified for a link: the person proved control of the provider account by completing the login while
  signed in, and the email is stored for display only.

## 7. Ownership

- An analysis belongs to the user who started it (`analyses.user_id`) and to nobody else, administrators included.
- `AnalysisService` is the **only** place that compares owners (`findOwned`, used by `getStatus`, `requireCompleted`, `delete`, `list`), with the caller from
  `AnalysisAccess` (the token's `sub`). Unknown, malformed, foreign and ownerless ids all give the same `404 ANALYSIS_NOT_FOUND`. A new endpoint that goes
  through `requireCompleted` is protected automatically.
- `GET /api/v1/analyses` lists only the caller's analyses; `DELETE` removes graph, metadata and row via `AnalysisCleanup` (409 while running).
- Analyses created before accounts existed have no owner, are invisible, and `LegacyAnalysisPurger` deletes them (graph first) at startup.

## 8. Premium

- `security/EntitlementService` reads the plan from PostgreSQL on every check. PREMIUM counts only while `plan_expires_at` is unset or in the future;
  a disabled or missing user is FREE. Services call `requirePremium(Feature)` themselves; there is no SpEL style.
- `graph/GraphPremiumGate` runs on `GET .../graph` **before** the analysis lookup (so a free user gets the same 403 whether or not the analysis
  exists). FREE may send no parameters, `nodeType`, `relationshipType` and `level=BEGINNER`. Everything else is `403 PREMIUM_FEATURE`: `level=INTERMEDIATE`,
  `level=EXPERT` (and any unknown level), `rootNodeId`, `depth`, `nodeTypes`, `relationshipTypes`, `direction`, `maxNodes`, and any parameter name not
  listed. The `Feature` enum already has constants for the V2 endpoints (`expand`, `relationships`, `methods`, `dependencies`), which do not exist yet.

## 9. Hardening

- **Rate limits** (`RateLimiter`: Bucket4j + Caffeine, in memory; TODO shared store before running several instances). `RateLimitFilter` sits after CORS
  and before authentication: login 10/min per IP; register + forgot-password + resend-verification 5/hour per IP (one shared bucket). Starting an analysis is
  limited to 10/hour per user inside `AnalysisService`. Refusals are `429 RATE_LIMITED` with `Retry-After`.
- **Client IP** is `request.getRemoteAddr()` (`ClientIpResolver`). Keep `server.forward-headers-strategy=native`: Tomcat's RemoteIpValve trusts
  `X-Forwarded-For` only from internal proxies (`server.tomcat.remoteip.internal-proxies`). With `framework`, Spring 7's `ForwardedHeaderFilter` replaces the
  remote address from the header with no trust check, letting anyone choose their own rate-limit key.
- **Audit log:** logger `security.audit` (`SecurityAuditLogger`): `event=... userId=... ip=...`, never a password, token, token hash or provider token
  (`AuditLoggingTest` checks a whole story).
- `TokenPurgeJob` (daily 03:17): deletes refresh and one-time tokens expired, revoked or used more than 7 days ago.
- `AdminBootstrapRunner`: creates the first admin from `ADMIN_EMAIL`/`ADMIN_PASSWORD` only if no admin exists; never promotes an account; a weak password stops startup.
- **Repository URLs:** `CreateAnalysisRequest` accepts only `https://github.com/{owner}/{repo}` (no credentials, `..`, other schemes or hosts) and `GitCloner`
  runs git with an argument list and `--`, never a shell.

## 10. Admin and profile

- `/api/v1/admin/users`: list (paged), get, `PATCH .../status {enabled}`. The route needs ROLE_ADMIN and `AdminUserService` re-checks in the database that the
  caller is an enabled admin (a token outlives its account). Disabling publishes `UserDisabledEvent`; `auth/SessionRevoker` revokes the sessions (so `user` does not
  depend on `auth`). Refused: disabling yourself (`CANNOT_DISABLE_SELF`) and the last enabled admin (`LAST_ADMIN`, with the admin rows locked). Responses
  contain no hashes or tokens.
- `/api/v1/users/me` (`GET`, `PATCH {name}`) and `/api/v1/auth/me` return the same `UserProfile` with the effective plan. The entity is never bound from a request.

## 11. Testing the security code

- `@WebMvcTest` slices import `security/SecuritySliceConfig` (test sources) and use `@WithMockUser(username = "<uuid>")` where the caller id matters;
  real-HTTP tests mint a token with `JwtService`; code that needs a caller outside a request uses `TestCaller.runAs`.
- Tests replace SMTP with `mail/MailTestConfig` (`RecordingEmailService`); `SmtpEmailServiceTest` uses GreenMail; the OAuth2 handshake runs against WireMock.
- The test profile raises the IP rate limits (every MockMvc request comes from 127.0.0.1); `RateLimitingIntegrationTest` sets low ones.
- `EveryEndpointIsProtectedTest` reads the real handler mappings: a new endpoint is automatically required to answer 401 without a token unless it is added to the
  explicit public list in that test; admin routes must answer 403 to a user.
- Use `./mvnw clean test` after changing constructors or signatures; an incremental build can leave stale test classes.

## 12. Deviations from spec.md

| Spec | Built | Why |
| --- | --- | --- |
| Password 8 to 128 characters | 8 to 72 bytes | bcrypt in Spring Security 7 rejects more |
| `csrf.spa()` | plain CSRF handler | `spa()` sets the cookie on every response |
| `forward-headers-strategy=framework` | `native` | `framework` lets clients forge their IP |
| `@PreAuthorize` ownership check | check inside `AnalysisService` | `@PreAuthorize` gives 403; ownership must give 404 |
| RFC 9457 Problem Details | `{ code, message }` | matches the rest of the API (decision recorded with the user) |
| Existing analyses migrated or assigned | deleted at startup | decision recorded with the user |
| Flyway if used | `ddl-auto=update` | the project has no migrations; **not for production** |
| `/actuator/**` rules | none | actuator is not a dependency; the final rule denies everything unlisted |
| Link a social login by verified email | refused if the account already has another identity of that provider | prevents takeover by a re-issued address |

## 13. Not built

`reanalyze`, `/usage`, `/admin/stats`, a role-change endpoint, `password_changed_at` access-token validation, a revocation list for access tokens (a
disabled user's access token works for up to 15 minutes everywhere except `/users/me`, `/auth/me` and admin calls, which check the database), MFA, payments.
