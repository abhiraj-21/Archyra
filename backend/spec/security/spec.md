# Spec: Security for Archyra (Spring Security 7, JWT, OAuth2 Login)

> **For Claude Code:** Archyra is an existing Spring Boot app (V0.1: repository analysis, code graph, REST API) with **no security yet**. This spec adds authentication, authorization, OAuth2 social login and premium-feature enforcement (the security side of roadmap V0.5 and the premium gates of V2/V3).
>
> Read the whole spec first. Then inspect the codebase (base package, entity style, DTO and exception conventions, test setup, existing controllers) and follow its conventions. Where this spec and the code conflict on *style*, follow the code. Where they conflict on *security behavior*, follow this spec. Work through the phases in §15 in order and keep `./mvnw verify` green after each one.
>
> **Scope:** security only. The product features around it (usage counters, stats queries, Razorpay billing, the AI chat, the graph itself) are separate work. This spec only secures them and gives them the hooks they need.

---

## 1. Decisions

| Area | Decision |
|---|---|
| Framework | Spring Boot **4.x**, Spring Security **7.x** |
| Build | **Maven**, **Java 25** |
| Database | **PostgreSQL** via Spring Data JPA / Hibernate (existing datasource). Neo4j holds the code graph and is not touched by this spec |
| Configuration | **`application.properties`** (not YAML). Use `application-dev.properties` / `application-prod.properties` profiles if the project already does |
| Login methods | Email + password **and** OAuth2 login with **Google** and **GitHub** |
| Tokens | Short-lived **access JWT** + opaque **refresh token** with rotation |
| JWT signing | **HS256**, shared secret |
| Token transport | Access token in the JSON body, sent as `Authorization: Bearer …`. Refresh token in an **HttpOnly, Secure, SameSite=Strict cookie** |
| OAuth2 flow | **Backend-handled redirect** (Spring `oauth2Login`). On success the backend sets the refresh cookie and redirects to the SPA. **No tokens ever appear in a URL** |
| Account linking | Auto-link an OAuth2 login to an existing account when the provider reports the email as **verified** |
| Client | React + TypeScript SPA (Vite) |
| Lifetimes | Access **15 min**, refresh **7 days** |
| Roles | `USER`, `ADMIN` |
| Plans | `FREE`, `PREMIUM` (a **plan**, not a role) |
| Email | Spring Mail over SMTP |
| Hardening | Baseline in §11 (adjustable) |

### Guiding principles
- Use Spring Security's **OAuth2 Resource Server** (`NimbusJwtEncoder` / `NimbusJwtDecoder`) for access tokens. **Do not hand-roll a JWT filter** and do not add `jjwt` or similar.
- **Lambda DSL only.** `WebSecurityConfigurerAdapter` and `.and()` chaining don't exist in Security 7.
- The API is **stateless**. No `HttpSession` for `/api/**`. (The OAuth2 handshake is the one exception, see §7.)
- Hash passwords with the delegating encoder. Store **only hashes** of refresh, verification and reset tokens (SHA-256).
- **Never reveal whether an email is registered** (register, login, forgot-password, resend-verification).
- **Never trust the frontend** for authorization or premium gating. Every check happens server-side.
- Boot 4 renamed and modularized several starters and packages (e.g. the OAuth2 starters now carry a `security-` prefix, and Jackson 3 lives under `tools.jackson.*`). **Verify every artifact and class name against the versions the project actually resolves.** If something named here doesn't exist, use the current equivalent and leave a one-line comment.

---

## 2. Dependencies (Maven)

Add what's missing; versions come from the Boot BOM.

- Security core (`spring-boot-starter-security`)
- **OAuth2 resource server** starter (JWT validation, Nimbus)
- **OAuth2 client** starter (Google/GitHub login)
- Validation, Mail, Data JPA (likely present)
- Bucket4j (current core artifact) and Caffeine, for rate limiting and small caches
- Test: `spring-security-test`, Testcontainers (PostgreSQL) if already used, GreenMail for SMTP, WireMock (or Spring's mock server support) to fake Google/GitHub in OAuth tests

Schema changes go in a **new migration** (Flyway/Liquibase if the project uses one). If it uses `ddl-auto=update`, note in the PR that this must not be used in production.

---

## 3. Configuration (`application.properties`)

Bind everything custom to a `@ConfigurationProperties("app.security")` record, `@Validated`.

```properties
# ---- JWT ----
app.security.jwt.secret=${JWT_SECRET}
app.security.jwt.issuer=${JWT_ISSUER:archyra}
app.security.jwt.access-token-ttl=15m

# ---- Refresh token / cookie ----
app.security.refresh-token.ttl=7d
app.security.refresh-token.cookie-name=refresh_token
app.security.refresh-token.cookie-path=/api/v1/auth
app.security.refresh-token.cookie-secure=true

# ---- One-time tokens ----
app.security.verification-token-ttl=24h
app.security.password-reset-token-ttl=30m

# ---- Frontend / CORS ----
app.security.frontend-base-url=${FRONTEND_BASE_URL:http://localhost:5173}
app.security.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:5173}
app.security.oauth2.success-redirect-path=/auth/callback
app.security.oauth2.failure-redirect-path=/login

# ---- Rate limiting / lockout ----
app.security.rate-limit.login-per-ip-per-minute=10
app.security.rate-limit.sensitive-per-ip-per-hour=5
app.security.lockout.max-failed-attempts=5
app.security.lockout.lock-duration=15m

# ---- Admin bootstrap (optional) ----
app.security.bootstrap-admin.email=${ADMIN_EMAIL:}
app.security.bootstrap-admin.password=${ADMIN_PASSWORD:}

# ---- Proxy awareness (needed so OAuth2 redirect URIs and client IPs are correct) ----
server.forward-headers-strategy=framework

# ---- OAuth2 clients ----
spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID}
spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET}
spring.security.oauth2.client.registration.google.scope=openid,profile,email

spring.security.oauth2.client.registration.github.client-id=${GITHUB_CLIENT_ID}
spring.security.oauth2.client.registration.github.client-secret=${GITHUB_CLIENT_SECRET}
spring.security.oauth2.client.registration.github.scope=read:user,user:email

# ---- Mail ----
spring.mail.host=${SMTP_HOST}
spring.mail.port=${SMTP_PORT:587}
spring.mail.username=${SMTP_USERNAME}
spring.mail.password=${SMTP_PASSWORD}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

- **No secrets in the repo.** Add an `.env.example` / README section listing every variable. Generate the JWT secret with `openssl rand -base64 32`.
- Fail startup if the decoded JWT secret is under 32 bytes, or if `cookie-secure=false` while the `prod` profile is active.
- `application-dev.properties` may set `cookie-secure=false` and use a local SMTP sink (MailHog or GreenMail).
- Redirect URIs to register with each provider: `{API_BASE_URL}/login/oauth2/code/google` and `{API_BASE_URL}/login/oauth2/code/github`. Document this in the README.

---

## 4. Data model

Adapt to the existing conventions. If a `User` entity already exists, **extend it**.

### `users`
| Column | Notes |
|---|---|
| id | UUID (or match existing PK type) |
| email | unique, lower-cased and trimmed |
| name | display name (the register request and `PATCH /users/me` use it) |
| password_hash | **nullable**: OAuth2-only users have none |
| role | enum `USER` / `ADMIN`, default `USER` |
| plan | enum `FREE` / `PREMIUM`, default `FREE` |
| plan_expires_at | nullable. Written later by billing. Security only *reads* it (see §9) |
| email_verified | boolean |
| enabled | boolean, default true |
| failed_login_attempts, locked_until | lockout |
| password_changed_at | |
| last_login_at | needed by the admin "active users" stat |
| created_at, updated_at | |

### `linked_identities`
| Column | Notes |
|---|---|
| id | PK |
| user_id | FK → users, indexed |
| provider | `GOOGLE` / `GITHUB` |
| provider_user_id | Google `sub` / GitHub numeric id (as string) |
| provider_email | email reported at link time |
| created_at | |
| **unique(provider, provider_user_id)** | |

Identity is matched by **(provider, provider_user_id)**, never by email alone after the first link.

### `refresh_tokens`
`id`, `user_id` (indexed), `token_hash` (SHA-256 hex, unique), `family_id` (indexed), `expires_at`, `created_at`, `revoked_at`, `replaced_by_id`, optional `user_agent`/`ip`.

### `one_time_tokens`
`id`, `user_id`, `purpose` (`EMAIL_VERIFICATION` / `PASSWORD_RESET`), `token_hash` (unique), `expires_at`, `used_at`.

Raw tokens: 32 bytes from `SecureRandom`, base64url, no padding. Only hashes are stored.

### Ownership on existing tables
Analyses must belong to a user. Add `analyses.user_id` (FK, indexed, **non-null for new rows**). The existing V0.1 rows have no owner: see Open Questions (§17) before migrating them. The `analysisId` values must be **non-guessable** (UUID or ≥ 128 bits of randomness). If the current short ids (e.g. `a8f31c`) are sequential or short, flag it.

A daily `@Scheduled` job purges expired or revoked refresh tokens and expired or used one-time tokens older than 7 days.

---

## 5. Access token (JWT)

- **Encoder:** `NimbusJwtEncoder` over an `ImmutableSecret`. **Decoder:** `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256)`, with an explicit HS256 `JwsHeader`.
- **Validators:** default timestamps (≤ 30s skew) and issuer match. Optional: reject tokens issued before the user's `password_changed_at` (small Caffeine cache). If skipped, say in the PR that password changes only take full effect when the access token expires.
- **Claims:** `iss`, `sub` = user id (**not** email), `iat`, `exp` (+15m), `jti`, `roles` (e.g. `["USER"]`). Optionally `email`.
- **Do not put `plan` in the JWT.** Plan changes (upgrade, expiry) must apply immediately, so the plan is read server-side at enforcement time (§9).
- **Authorities:** `JwtAuthenticationConverter` with claim `roles`, prefix `ROLE_`, so `hasRole("ADMIN")` works.
- All JWT creation lives in **one** class (`JwtService`).
- **The same access token is issued by every login path**: password, Google and GitHub all end in the same `AuthTokens` issuance code.

---

## 6. Refresh token & cookie

- Opaque random string, not a JWT.
- Cookie via `ResponseCookie`: `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=604800`.
- **Rotation:** each `/refresh` revokes the presented token, issues a new one in the **same family** (`replaced_by_id` set) and a new access token.
- **Reuse detection:** presenting an already-revoked token means theft. Revoke the **entire family**, return 401, audit-log it.
- **Logout:** revoke the family and clear the cookie (same name and path, `Max-Age=0`).
- **Password reset/change, user disabled:** revoke **all** the user's refresh tokens.
- **SameSite note:** `Strict` works when the SPA and API share a registrable domain (`localhost:5173` + `localhost:8080` in dev; `app.example.com` + `api.example.com` in prod). Document this requirement. If they ever end up on different sites, the cookie strategy must be revisited, so don't silently weaken it.

### CSRF
`/api/v1/auth/refresh` and `/logout` authenticate by cookie, so they need CSRF protection:
- Enable CSRF with the SPA-friendly setup (`CookieCsrfTokenRepository` with a readable `XSRF-TOKEN` cookie, token echoed in the `X-XSRF-TOKEN` header; use the `csrf.spa()` configuration if available in Security 7).
- Apply it **only** to those cookie-authenticated endpoints. Bearer-authenticated `/api/**` endpoints are CSRF-exempt.
- Public `GET /api/v1/auth/csrf` issues the cookie.
- `SameSite=Strict` is a second layer, not a replacement.

---

## 7. OAuth2 login (Google + GitHub)

### Flow
```
SPA: link/redirect to  {API}/oauth2/authorization/{google|github}
  → provider consent → {API}/login/oauth2/code/{provider}   (Spring handles the code exchange)
  → backend resolves/creates the user, creates a refresh-token family
  → sets refresh cookie, 302 → {frontend-base-url}/auth/callback
SPA callback page: GET /api/v1/auth/csrf, then POST /api/v1/auth/refresh (credentials: include)
  → receives { accessToken, … } exactly like a normal login
```
- Keep Spring's default OAuth2 endpoints. Don't build custom OAuth routes.
- **Redirect targets come only from configuration** (`frontend-base-url` + path). Never from a request parameter (open-redirect risk).
- **No access token, refresh token or other secret in the redirect URL.**

### Filter-chain layout
Use **two `SecurityFilterChain` beans**:
1. `@Order(1)`, matching `/oauth2/**` and `/login/oauth2/**`: `oauth2Login` enabled, session policy `IF_REQUIRED`. The session exists **only** to hold the authorization request (state/PKCE) during the handshake. The success and failure handlers **invalidate the session** and delete `JSESSIONID`.
2. `@Order(2)`, everything else: stateless, resource server, JWT (§8).

(A cookie-based `AuthorizationRequestRepository` is an acceptable alternative if you'd rather have no session at all, but it must be signed or encrypted. Pick one and document why.)

### User resolution (`OAuth2UserService` / `OidcUserService` wrapper)
1. Extract `provider`, `provider_user_id`, `email`, `emailVerified`, `name`.
    - **Google (OIDC):** `sub`, `email`, `email_verified`, `name`. Require `email_verified == true`.
    - **GitHub (plain OAuth2):** numeric `id`, `name`/`login`. The email is often private, so call `GET https://api.github.com/user/emails` with the access token and use the entry that is **`primary` and `verified`**. If there is none, **fail the login** with `OAUTH_EMAIL_UNVERIFIED`. Never use the public profile `email` field, which isn't guaranteed to be verified.
2. If a `linked_identities` row exists for `(provider, provider_user_id)` → log in that user.
3. Else, if a user exists with that email:
    - provider email verified → **link** (insert `linked_identities`), log in.
    - **Pre-hijack protection:** if that existing user has `email_verified=false`, someone may have registered the address with a password they know. When linking, **null the `password_hash`**, revoke their refresh tokens and mark the email verified. The real owner can set a password later via forgot-password.
4. Else → **create** a user: `role=USER`, `plan=FREE`, `email_verified=true`, `password_hash=null`, `name` from the provider, plus the identity row.
5. Reject disabled users (`OAUTH_ACCOUNT_DISABLED`). Update `last_login_at`.

OAuth2-only users (no password) can use **forgot-password** to add a password (they've proven control of the email). Password login for an account without a hash fails with the same generic "Invalid email or password".

### Failure handler
Redirect to `{frontend-base-url}{failure-redirect-path}?error=<code>` where `<code>` is one of a **fixed set** (`oauth_failed`, `oauth_email_unverified`, `oauth_account_disabled`, `oauth_cancelled`). Never put exception messages in the URL.

### Audit
Log link, create and login events per provider (§11), never tokens.

---

## 8. Authorization rules

Everything lives under `/api/v1`. Stateless chain (`@Order(2)`):

```
/api/v1/auth/**                    → permitAll   (refresh/logout enforced by cookie + CSRF, not by JWT)
/api/v1/auth/me                    → authenticated
/oauth2/**, /login/oauth2/**       → handled by chain #1
/actuator/health, /actuator/info   → permitAll
/actuator/**                       → hasRole("ADMIN")
/api/v1/admin/**                   → hasRole("ADMIN")
/error                             → permitAll
/api/v1/**                         → authenticated
anyRequest                         → denyAll
```
(Order the matchers so the specific `/api/v1/auth/me` rule precedes the `/api/v1/auth/**` permit-all.)

Enable `@EnableMethodSecurity`.

### Ownership (critical)
Every `/api/v1/analyses/{analysisId}/**` endpoint (status, repository, files, classes, graph, nodes, methods, architecture, chat, explain, delete, reanalyze) **must verify the analysis belongs to the caller**. Implement it once, e.g. an `AnalysisAccess` bean:
```java
@PreAuthorize("@analysisAccess.isOwner(#analysisId, authentication)")
```
- Non-owner or missing → **404** (not 403), so ids can't be probed.
- `GET /api/v1/analyses` lists **only the caller's** analyses.
- `ADMIN` does **not** get blanket read access to users' analyses. Admins see aggregate stats and user records, not other people's code graphs.
- Future conversation endpoints (`/api/v1/conversations/**`) follow the same ownership rule.

### The existing endpoints
Walk the existing controllers and produce a table in the PR: endpoint → public / `USER` (+ owner check) / `ADMIN` / premium. Confirm it with the user if anything is unclear. Don't silently guess.

### Admin bootstrap
At startup, if both `bootstrap-admin.*` properties are set and **no admin exists**, create one (verified, enabled, `ADMIN`). Otherwise skip. Admins are **never** created through register or OAuth2.

---

## 9. Premium enforcement

Premium is enforced **in the backend**, never in the UI.

- `EntitlementService`:
    - `Plan effectivePlan(userId)`: `PREMIUM` only if `plan == PREMIUM` and (`plan_expires_at` is null or in the future). Read from the DB with a very short cache (≤ 30s) or none.
    - `void requirePremium(Feature feature)`: throws `PremiumRequiredException`.
- Expose it to method security:
  ```java
  @PreAuthorize("@entitlements.canUse(authentication, 'EXPERT_GRAPH')")
  ```
  or call `requirePremium` inside the service. Pick one style and use it consistently.
- Failure → **403** with Problem Details and `code = "PREMIUM_FEATURE"`, e.g. `"Expert graph requires Archyra Premium."` (matches the product spec).
- **Gating rules** (from the roadmap):

| Request | FREE | PREMIUM |
|---|---|---|
| `GET …/graph` with `level` absent or `BEGINNER` | ✅ | ✅ |
| `GET …/graph?level=INTERMEDIATE` | ❌ 403 (or a limited result if product decides) | ✅ |
| `GET …/graph?level=EXPERT` | ❌ 403 | ✅ |
| Interactive params on `…/graph`: `rootNodeId`, `depth`, `nodeTypes`, `relationshipTypes`, `direction`, `maxNodes` | ❌ 403 | ✅ |
| `…/nodes/{id}/expand`, `…/nodes/{id}/relationships` | ❌ 403 | ✅ |
| `…/classes/{id}/methods`, `…/methods/{id}/dependencies` | ❌ 403 | ✅ |
| `POST …/chat`, `POST …/explain` | allowed (volume limited by usage quotas, out of scope) | allowed, higher limits |
| Basic repository, files, classes, `/graph` (beginner), `/architecture` | ✅ | ✅ |

The `graph` endpoint is shared, so the gate depends on its **query parameters**. Implement it as an explicit check in the service or a dedicated interceptor, not by relying on the frontend to omit parameters. Treat any unknown or unlisted advanced parameter on a free request as premium-only.
- **Payments and usage counting are out of scope**, but leave the integration points: `plan`/`plan_expires_at` on the user, and `EntitlementService` as the single place that answers "what may this user do?" so a later `UsageService` and Razorpay webhook plug in without touching controllers.
- Don't reveal premium-only data in a free response (no partial deep graph, no counts of hidden nodes).

---

## 10. Endpoints

### Auth: `/api/v1/auth`
| Method & path | Auth | Purpose |
|---|---|---|
| `POST /register` | public | Create user (`email`, `password`, `name`), send verification email |
| `POST /login` | public | Password login → access token + refresh cookie |
| `POST /refresh` | cookie + CSRF | Rotate refresh token → new access token |
| `POST /logout` | cookie + CSRF | Revoke family, clear cookie |
| `GET /csrf` | public | Issue XSRF cookie |
| `GET /me` | bearer | `{ id, name, email, role, plan, emailVerified, linkedProviders }` |
| `POST /verify-email` | public | Consume verification token |
| `POST /resend-verification` | public | Resend verification email |
| `POST /forgot-password` | public | Send reset email |
| `POST /reset-password` | public | Consume reset token, set password |

OAuth2 entry points are Spring's own: `GET /oauth2/authorization/google` and `/github`.

### Users: `/api/v1/users`
- `GET /me`, `PATCH /me` (`{ "name": "…" }` only). **Never** let this endpoint change `email`, `role`, `plan` or `enabled` (use an explicit DTO, never bind the entity).

### Admin: `/api/v1/admin` (`ADMIN` only)
Secure the endpoints already planned in the product doc: `GET /users` (paged), `GET /users/{userId}`, `GET /stats`, `PATCH /users/{userId}/status` (`{ "enabled": false }`). Security requirements only (the stats queries are product work):
- Disabling a user **revokes all their refresh tokens**.
- An admin can't disable or demote **themselves**, and the last remaining admin can't be disabled.
- Responses never include `password_hash`, token hashes or raw secrets.
- Optional: `PATCH /users/{userId}/role` with the same self-demotion protection.

### Behavior details

**register** `{ email, password, name }`: normalize the email. Password 8–128 characters, not equal to the email, optionally checked against a small common-passwords list (no composition rules). Always respond **202** with the same generic message, whether or not the email exists. New users: `USER`, `FREE`, unverified. Existing email → send nothing new (optionally notify the owner).

**login** `{ email, password }`: use `AuthenticationManager` + `DaoAuthenticationProvider` + a `UserDetailsService` loading by email. Checks in order: rate limit → lockout → credentials → `enabled` → `email_verified`.
- Bad credentials or locked → **401** `INVALID_CREDENTIALS` (generic, lockout not revealed). Increment failures and lock at the threshold.
- Correct password but unverified → **403** `EMAIL_NOT_VERIFIED`.
- Success → reset the counter, set `last_login_at`, new refresh family, **200** `{ accessToken, tokenType: "Bearer", expiresIn: 900 }`.
- Unknown email still performs a dummy hash comparison (timing).

**refresh**: reads the cookie, same response body as login. Any failure → **401** and clear the cookie. Disabled user → 401.

**logout**: always **204**, clears the cookie.

**verify-email** `{ token }`: single use, unexpired → verified. Otherwise **400** `INVALID_OR_EXPIRED_TOKEN`.

**resend-verification / forgot-password** `{ email }`: always **202** with a generic message, rate limited, previous unused tokens invalidated.

**reset-password** `{ token, newPassword }`: validate, update hash and `password_changed_at`, mark the token used, **revoke all refresh tokens**, reset the lockout. **200**. No auto-login.

### Emails
`EmailService` interface + `SmtpEmailService` (`JavaMailSender`). Send **asynchronously** (bounded `@Async` executor) so response timing doesn't reveal account existence. Links: `{frontend-base-url}/verify-email?token=…`, `{frontend-base-url}/reset-password?token=…`. Never log tokens or links above DEBUG.

---

## 11. Error handling & hardening

### Errors
RFC 9457 **Problem Details** with an extra `code` property, integrated with any existing `@RestControllerAdvice` (see Open Questions). Custom `AuthenticationEntryPoint` (401, keep the `WWW-Authenticate: Bearer` header) and `AccessDeniedHandler` (403). Codes: `INVALID_CREDENTIALS`, `EMAIL_NOT_VERIFIED`, `ACCOUNT_DISABLED`, `INVALID_OR_EXPIRED_TOKEN`, `REFRESH_TOKEN_INVALID`, `PREMIUM_FEATURE`, `RATE_LIMITED` (429 + `Retry-After`), `VALIDATION_FAILED`, `ACCESS_DENIED`. No stack traces, SQL or "user not found" leaks.

### Hardening baseline (adjustable)
1. **Rate limiting** (Bucket4j filter before authentication, per client IP): `/login` 10/min; `/register`, `/forgot-password`, `/resend-verification` 5/hour. 429 + `Retry-After`. In-memory, bounded (Caffeine). Add a TODO that multi-instance deployments need a shared store (Redis is already on the roadmap). Resolve the client IP via the framework forwarded-headers strategy, don't blindly trust `X-Forwarded-For`.
2. **Lockout:** 5 failures → 15 min, temporary only.
3. **Refresh reuse detection** (§6).
4. **CORS:** a `CorsConfigurationSource` bean, origins from config (never `*`), `allowCredentials=true`, headers `Authorization, Content-Type, X-XSRF-TOKEN`.
5. **Headers:** Spring defaults plus HSTS (1 year, includeSubDomains), `Referrer-Policy: no-referrer`, and a strict CSP for API responses (`default-src 'none'; frame-ancestors 'none'`).
6. **Audit log** (structured log lines): login success/failure, lockout, OAuth2 login/link/create, refresh reuse, password reset, user disabled, role change. Include user id, IP and event. **Never log passwords, tokens, token hashes or OAuth2 access tokens.**
7. **Password encoding:** `PasswordEncoderFactories.createDelegatingPasswordEncoder()`.
8. **Repository URL handling:** `POST /api/v1/analyses` accepts a user-supplied GitHub URL that the server then clones. That's an SSRF and command-injection surface. In this spec's scope, require the endpoint to be authenticated and rate limited; additionally **validate** that the URL is `https://github.com/{owner}/{repo}` (no other hosts, no `file:`/`ssh:`, no credentials in the URL) and that the git call never goes through a shell string. Flag any deviation found in the existing ingestion code instead of rewriting it.

---

## 12. Suggested package structure
Adapt to the existing layout:
```
security/
  config/     SecurityConfig (2 chains), SecurityProperties, CorsConfig, JwtConfig
  jwt/        JwtService, PasswordChangedAtValidator
  auth/       AuthController, AuthService, AuthTokens, dto/ (records)
  oauth2/     OAuth2UserResolver (Google/GitHub), OAuth2SuccessHandler, OAuth2FailureHandler, GitHubEmailClient
  token/      RefreshToken(+Repo/Service), OneTimeToken(+Repo/Service), TokenHasher
  user/       AppUserDetailsService, LinkedIdentity(+Repo), LoginAttemptService
  access/     AnalysisAccess (ownership), EntitlementService, PremiumRequiredException
  ratelimit/  RateLimitFilter
  web/        RestAuthenticationEntryPoint, RestAccessDeniedHandler, CookieUtils
  audit/      SecurityAuditLogger
  admin/      AdminBootstrapRunner (+ security rules for the admin controllers)
mail/         EmailService, SmtpEmailService
```
Records for DTOs, constructor injection only, inject a `Clock` wherever time matters.

---

## 13. Testing requirements

**Unit:** `JwtService` (valid, expired, wrong signature, wrong issuer); refresh rotation and family revocation; one-time-token single use and expiry; lockout with a fixed `Clock`; `EntitlementService` (plan, expiry); OAuth2 user resolution for each branch below.

**Integration** (`@SpringBootTest`, PostgreSQL via Testcontainers if the project uses it, GreenMail for SMTP, mocked provider endpoints for OAuth2):
- Register → email → verify → login → call `/api/v1/auth/me` → refresh → logout → refresh fails.
- Unverified login → 403 `EMAIL_NOT_VERIFIED`. 5 bad passwords → locked.
- Refresh reuse → family dead. Refresh without the CSRF header → 403.
- Forgot/reset: unknown email → 202 and no mail. Reset revokes old sessions.
- **OAuth2:**
    - new Google user → created, verified, `FREE`, cookie set, redirect to the configured SPA URL with **no token in the URL**
    - returning user matched by `(provider, sub)` even if their email changed
    - existing verified password user + Google verified email → linked, same user id
    - existing **unverified** password user + OAuth2 → linked, **password hash cleared**, old refresh tokens revoked
    - Google `email_verified=false` → rejected
    - GitHub with no primary verified email → rejected
    - disabled user → rejected, failure redirect has a fixed error code
    - handshake session invalidated after success
    - redirect target can't be influenced by request parameters
- **Authorization:** USER → `/api/v1/admin/**` 403; ADMIN 200; no/tampered/expired token 401.
- **Ownership:** user A can't read, delete or chat on user B's analysis (404). `GET /analyses` returns only their own. ADMIN also can't read another user's graph.
- **Premium:** FREE gets 403 `PREMIUM_FEATURE` for `level=EXPERT`, `level=INTERMEDIATE`, `rootNodeId`/`depth` params, `expand`, `relationships`, `methods`, `dependencies`. PREMIUM passes. An expired `plan_expires_at` behaves as FREE. A `plan` claim in the JWT (if someone forges one) changes nothing.
- Rate limit → 429 + `Retry-After`. CORS allowed vs disallowed origin.
- Register/forgot/resend responses are **identical** for known and unknown emails.
- Disabling a user kills their refresh tokens. An admin can't disable themselves.
- Existing tests still pass (update with `jwt()` / `@WithMockUser`).

---

## 14. Frontend integration notes (put in the README)
- Login with Google/GitHub = full-page navigation to `{API}/oauth2/authorization/{provider}`.
- `/auth/callback` route: `GET /api/v1/auth/csrf` → `POST /api/v1/auth/refresh` with `credentials: 'include'` and the `X-XSRF-TOKEN` header → keep the access token **in memory only** (not localStorage).
- On app load and on any 401: try the same refresh once, then send the user to login.
- Handle `403 PREMIUM_FEATURE` by showing an upgrade prompt. The UI may hide premium controls, but the backend remains the authority.
- Show `/login?error=…` codes as friendly messages.

---

## 15. Implementation phases
1. **Dependencies & properties:** deps, `SecurityProperties`, secret validation. App still boots.
2. **User model & persistence:** `users` changes, `Role`, `Plan`, `linked_identities`, `analyses.user_id`, migration.
3. **JWT infrastructure:** encoder/decoder, `JwtService`, authorities converter, tests.
4. **Stateless filter chain:** resource server, rules from §8, entry point, access-denied handler, CORS, headers. Secure existing endpoints and publish the endpoint table.
5. **Password auth:** register/login/refresh/logout/me, refresh tokens, cookie, rotation, reuse detection, CSRF.
6. **Email flows:** verification, reset.
7. **OAuth2 login:** chain #1, Google and GitHub user resolution, linking rules, handlers, tests.
8. **Ownership & premium:** `AnalysisAccess`, `EntitlementService`, gates on `graph` and the V2/V3 endpoints, and the repository-URL validation from §11.8.
9. **Hardening & admin:** rate limiting, lockout, audit logging, admin bootstrap and admin-endpoint rules.
10. **Tests & docs:** finish §13, README env vars, provider setup and §14.

---

## 16. Acceptance criteria
- [ ] No endpoint is reachable without a valid token except the explicit public list. Wrong role → 403.
- [ ] Users can only access **their own** analyses; others get 404.
- [ ] Premium-only graph levels and interactive features return 403 `PREMIUM_FEATURE` for FREE users, enforced server-side and based on the DB, not the JWT.
- [ ] Password, Google and GitHub logins all produce the same kind of session (15 min access JWT + HttpOnly refresh cookie).
- [ ] OAuth2 never exposes tokens in URLs, only trusts **verified** provider emails, and cannot be used to take over a pre-registered unverified account.
- [ ] Refresh tokens are hashed, rotated, reuse-detected, CSRF-protected and revoked on logout, reset and disable.
- [ ] No endpoint reveals whether an email is registered.
- [ ] Brute force is throttled. No secrets, passwords or tokens in logs, responses (apart from the issued access token) or source control.
- [ ] Configuration is entirely in `application.properties`, with secrets from environment variables.
- [ ] §13 tests pass and `./mvnw verify` is green.

---

## 17. Open questions for Claude Code to raise (don't guess)
1. **Existing analyses have no owner.** Options: delete them, assign them to the first admin, or leave `user_id` nullable and hide them from everyone. Ask before migrating.
2. Which existing endpoints (besides the documented ones) should be public vs authenticated? Propose a mapping and confirm it.
3. If a `User`/account entity already exists with a different id type or username-based login, propose how to reconcile it before migrating.
4. If there's a global exception handler with a non-Problem-Details format, ask whether to switch or match it.
5. Should `INTERMEDIATE` graphs be a hard 403 for FREE or a limited result? (The roadmap says "❌/limited.") Default to 403 until told otherwise.
6. Are the SPA and API going to be deployed on the **same site**? If not, the `SameSite=Strict` refresh cookie needs a different design.

## 18. Out of scope
Razorpay billing and the code that sets `plan`, usage counters and free-tier limits, admin stats queries, the AI/RAG features, Redis/RabbitMQ, MFA, other OAuth2 providers, fine-grained permissions beyond roles + plan, a server-side revocation list for access tokens, and the frontend implementation.