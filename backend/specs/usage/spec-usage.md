# Spec: Persistent Usage Tracking & Free-Tier Limits (Archyra V0.5)

> **For Claude Code:** Implement this feature in the existing Archyra backend. Read this whole file first. Then read `Architecture.md`, the root `CLAUDE.md` and `backend/CLAUDE.md`, and **inspect before writing code**: the `analysis/`, `user/`, `security/` (especially `EntitlementService` and `EveryEndpointIsProtectedTest`) and `common/` packages, `application.properties` and `pom.xml`.
>
> Class and method names below are *suggestions*. Where the project's conventions (naming, DTO style, exception handling, test layout) differ, **follow the project**. Where this spec and the code disagree on *behavior*, follow this spec, and mention the conflict in your final summary.
>
> **Out of scope. Do not build:** any AI functionality or AI endpoint (`AI_QUERY` exists only for future extensibility), billing/Razorpay, admin tools for adjusting quotas, monthly or periodic resets, frontend changes.

---

## 1. Goal

Free users get a **lifetime allowance** of repository analyses (default 5). Usage is persisted per user and per usage type in PostgreSQL, limits come from configuration, and the limit is enforced **atomically** when an analysis is created. Premium users are never blocked. Failed analyses don't burn quota. Duplicate in-flight requests aren't double-charged. A new authenticated endpoint, `GET /api/v1/usage`, lets the frontend show `3/5`.

---

## 2. Usage model (`usage/` feature package)

Create a `usage/` package following the same feature-package layout as the others (entity, repository, service, controller, DTOs, properties, exception).

### `UsageType` enum
```java
public enum UsageType { REPOSITORY_ANALYSIS, AI_QUERY }
```
`AI_QUERY` is only a placeholder. Nothing may consume it yet. Every piece of code here must work generically over `UsageType` so a new category later means one enum value + one property.

### `user_usage` table / `UserUsage` entity
| Column | Notes |
|---|---|
| id | PK (match project id convention) |
| user_id | FK → users, indexed, not null |
| usage_type | `@Enumerated(EnumType.STRING)`, not null |
| used_count | int, not null, default 0, **check `used_count >= 0`** |
| created_at, updated_at | match the project's auditing convention |

- **Unique constraint on `(user_id, usage_type)`.** One row per user per type, independent counters.
- Rows are created **lazily** the first time a user consumes that type. Reading usage must never create rows.
- Schema approach: follow `backend/CLAUDE.md`. If the project uses Hibernate `ddl-auto=update` and has no Flyway/Liquibase in `pom.xml`, declare the constraints on the entity (`@Table(uniqueConstraints=…)`, `columnDefinition` defaults) and **do not add a migration tool**. If Flyway/Liquibase *is* configured, write a migration instead.

### Additions to `Analysis` (needed for idempotent refunds)
| Column | Notes |
|---|---|
| quota_charged | boolean, **not null, default false**. True when this analysis consumed a unit |
| quota_refunded | boolean, **not null, default false**. True once the unit was returned |

Under `ddl-auto=update`, adding a non-null column to a table with rows fails in PostgreSQL unless there's a default, so use `columnDefinition = "boolean not null default false"`. Existing (pre-feature) analyses keep `false`/`false`, so they can never trigger a refund.

---

## 3. Configurable limits

`@ConfigurationProperties` (validated, immutable record or class, matching how other properties are bound in this project):

```properties
archyra.usage.free-tier.repository-analysis-limit=5
archyra.usage.free-tier.ai-query-limit=20
```

- `@Validated`, `@Min(0)` on each limit. Missing or negative values fail startup.
- Expose a single lookup, e.g. `int freeLimit(UsageType type)`, implemented with an exhaustive `switch` over the enum so adding a type without a limit is a **compile error**.
- **No limit number may appear in Java code or tests as a literal that should have come from config.** Tests bind their own values through properties or a constructed properties object.
- Add the properties (with a short comment) to `application.properties`. Check whether test configuration needs the same keys.

---

## 4. Plan handling

- Reuse the existing logic: `EntitlementService` and `User.effectivePlan()` decide whether a user is `PREMIUM` right now (including expiry). **Do not re-implement or duplicate plan logic**, and don't read the plan from the JWT.
- **FREE users:** consumption is bounded by the free-tier limit for that type.
- **PREMIUM users:** never blocked and never capped. Still **record** usage accurately (increment on create, release on failure) so metrics stay correct. In the API they appear as unlimited (§7).
- **Downgrade behavior (intentional):** the allowance is *lifetime*. `used_count` counts everything the user ever consumed, so a former premium user who has used 12 analyses and is back on `FREE` is already over the limit and gets blocked until the limit is raised. Document this in `Architecture.md`.
- Existing premium *feature* gating (`PREMIUM_FEATURE`) is untouched.

---

## 5. Enforcement in `AnalysisService.createAnalysis`

### 5.1 Transaction structure
`createAnalysis` **stays non-transactional overall**. The `Analysis` row must be committed **before** the `@Async` `AnalysisPipeline` is started. Put the transactional work in a **separate Spring bean** (e.g. `AnalysisRegistrar` / `AnalysisIntake`) so the proxy actually applies. Self-invocation on `@Transactional` is a classic bug here.

```
createAnalysis(user, request):                       // NOT @Transactional
    result = registrar.registerOrReuse(user, url)    // ONE short transaction, commits on return
    if result.reused: return existing analysis       // no charge, no pipeline start
    try:
        pipeline.run(result.analysisId)              // @Async, after commit
    catch (TaskRejectedException / RejectedExecutionException):
        usageService.refundForAnalysis(analysisId)   // §5.4
        <follow existing handling of the row> ; throw 503 SERVICE_BUSY
    return accepted(result.analysis)
```

`registerOrReuse` runs in **one transaction** that does, in this order:
1. Ensure the `(user, REPOSITORY_ANALYSIS)` usage row exists (`INSERT … ON CONFLICT DO NOTHING`, native query, so concurrent first-time requests can't violate the unique constraint).
2. **Lock that row**: `SELECT … FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)` with a lock-timeout hint, e.g. 5s, so a stuck holder can't pile up request threads). This serializes *all* analysis creation for that one user (other users are unaffected) and makes steps 3–5 race-free.
3. **Duplicate check** (§5.3). If an identical in-flight analysis exists → return it, **no charge**.
4. **Limit check:** if the effective plan is `FREE` and `used_count >= limit` → throw `UsageLimitExceededException`. (Premium skips this check.)
5. Increment `used_count`, insert the `Analysis` (`QUEUED`, `quota_charged = true`). Both commit together or not at all, so there's no window where quota is consumed without an analysis row, or vice versa.

> The pessimistic row lock is the chosen concurrency mechanism. If you can show a simpler, equally safe approach in this codebase (e.g. a guarded `UPDATE … WHERE used_count < :limit`), you may use it, but it **must also solve the duplicate-request race in §5.3**, not just the limit race. Explain the choice in your summary.

### 5.2 Concurrency guarantee
A free user firing N parallel `POST /api/v1/analyses` requests, starting at `0/limit` or `limit-1/limit`, must end with **exactly** `limit` consumed units at most. Never `limit + k`, and no `used_count` drift. Proven by a real multi-threaded test (§9).

### 5.3 Duplicate requests (no double charge)
First check what `AnalysisService` already does for duplicates and follow its conventions. If it has none, implement:
- "Identical" = **same authenticated user + same normalized repository URL**, reusing the project's existing URL validation/normalization (don't write a second normalizer).
- "In progress" = status in `QUEUED, CLONING, PARSING, ANALYZING, BUILDING_GRAPH`.
- On a match: return the **existing** analysis using the same response shape and status code as a normal create (`{ analysisId, status }`), start no new pipeline, consume nothing.
- The duplicate check runs **before** the limit check, so a user at `5/5` who resubmits a URL that's still running gets the existing analysis, not a 403.
- A previously `COMPLETED` or `FAILED` analysis of the same URL is **not** a duplicate; a new run is a new charge (this is also what "retry after failure" is).
- Because the check runs under the per-user lock from §5.1, two *simultaneous* identical requests produce one analysis and one charge.

### 5.4 Failures and idempotent refunds
Release the consumed unit **exactly once** when:
- the pipeline marks the analysis `FAILED` (every failure path in `AnalysisPipeline`, including top-level catch blocks), or
- submission is rejected with `503 SERVICE_BUSY` because the executor queue is full.

**Do not** refund when an analysis is `COMPLETED`, and **never** refund because the user deleted it.

Implement one method, `UsageService.refundForAnalysis(analysisId)`, in a transaction:
```
1. UPDATE analyses SET quota_refunded = true
   WHERE id = :id AND quota_charged = true AND quota_refunded = false
   → rows == 0: already refunded, never charged, or row gone → do nothing, return
2. rows == 1: UPDATE user_usage SET used_count = used_count - 1
   WHERE user_id = :userId AND usage_type = :type AND used_count > 0
```
Step 1 is the idempotency guard. Calling the method any number of times, from any thread, decrements at most once. The `used_count > 0` guard plus the DB check constraint prevent going negative.

Notes:
- If the refund fails while recording `FAILED`, log it with the analysis id at `ERROR` and **don't** swallow the `FAILED` status. Prefer doing both in one transaction if the pipeline's structure allows it.
- If the analysis row no longer exists (deleted while running), no refund happens. This is intended and keeps delete from being a quota lever. Mention it in the docs.
- If the project has a startup/recovery job that fails stuck analyses, route it through the same refund method.
- `DELETE /api/v1/analyses/{id}` must **not** touch the usage counter. Add a test.

### 5.5 Other entry points
Any other code path that starts a pipeline run is "creating an analysis". `POST /api/v1/analyses/{id}/reanalyze`, if it exists, must go through the **same** registrar and consume one unit. If you find such a path, wire it in and say so in the summary. Don't leave a bypass.

---

## 6. Error contract

New `UsageLimitExceededException`, handled in the **existing central `@RestControllerAdvice`** (in `common/`), producing the project's standard error body:

```json
{ "code": "USAGE_LIMIT_EXCEEDED", "message": "You have used all 5 free repository analyses. Upgrade to Archyra Premium to continue." }
```

- **HTTP status:** use **403** by default. It matches the existing `PREMIUM_FEATURE` convention, and a lifetime limit isn't a "retry later" situation (which is what 429 implies). If `common/` already uses 429 for quota-style errors, follow that instead, and say which you chose and why.
- The message may include the limit (read from config, never hardcoded) but must not include other users' data.
- Keep exactly the project's error fields; don't invent a new error format.

---

## 7. `GET /api/v1/usage`

- **Authenticated.** Resolve the user **only** from the security context, the same way other controllers do. **No** `userId` parameter, header or body field. Verify `EveryEndpointIsProtectedTest` passes. If it needs a rule for the new route, add the route to the authenticated set.
- Read-only: never creates rows or locks. A user with no row sees `used: 0`.
- Return one entry for **every** `UsageType`:

**FREE user at 3/5 analyses:**
```json
{
  "plan": "FREE",
  "usage": [
    { "usageType": "REPOSITORY_ANALYSIS", "used": 3, "limit": 5,  "remaining": 2,  "limitReached": false },
    { "usageType": "AI_QUERY",            "used": 0, "limit": 20, "remaining": 20, "limitReached": false }
  ]
}
```

**FREE user at the cap:** `used: 5, limit: 5, remaining: 0, limitReached: true`.

**PREMIUM user (unlimited, usage still recorded):**
```json
{
  "plan": "PREMIUM",
  "usage": [
    { "usageType": "REPOSITORY_ANALYSIS", "used": 12, "limit": null, "remaining": null, "limitReached": false },
    { "usageType": "AI_QUERY",            "used": 0,  "limit": null, "remaining": null, "limitReached": false }
  ]
}
```

Rules: `remaining = max(limit - used, 0)`. `limitReached = used >= limit` for FREE, always `false` for PREMIUM. `limit`/`remaining` are `null` (not `-1`, not omitted) for unlimited. A former premium user over the limit shows `used > limit`, `remaining: 0`, `limitReached: true`. There is no `resetAt` (the allowance is lifetime). Any "unlimited" decision comes from `effectivePlan()`, not from the stored plan field.

Final DTO names are yours to choose (records preferred if the project uses them). **Document the final shape** (§10).

---

## 8. Security & integrity checklist
- [ ] The usage endpoint can only ever show the caller's own data (no id parameters, no admin override).
- [ ] No endpoint, log line or error message exposes another user's counts.
- [ ] All quota writes happen server-side. Nothing in a request body or header can alter a counter.
- [ ] Counters can't go below 0 (DB check + guarded update) or exceed the free limit for free users.
- [ ] No new public endpoints. `EveryEndpointIsProtectedTest` passes.
- [ ] Limits come only from configuration.

---

## 9. Testing (`./mvnw clean test` must pass)

Follow the existing test structure and base classes (Testcontainers PostgreSQL setup, security test helpers). In tests, **stub or replace `AnalysisPipeline`** (and the executor where needed) so no real repository is cloned and tests are deterministic. Use `CountDownLatch`/`CyclicBarrier` for concurrency, never `Thread.sleep` to "wait for" races.

**Unit tests**
- Properties binding and validation (valid values, negative → startup failure, exhaustive limit lookup).
- `remaining` / `limitReached` calculations (free, at cap, over cap, premium → nulls).
- `refundForAnalysis` logic: once-only, never-charged, already-refunded, floor at 0.

**`@WebMvcTest` slice (`GET /api/v1/usage`)**
- 401 without authentication. 200 with it. JSON shape matches §7 exactly (FREE `3/5`, at cap, PREMIUM nulls).
- The service is called with the authenticated user's id. Any `userId` query parameter is ignored.
- `USAGE_LIMIT_EXCEEDED` is mapped to the agreed status + `{code, message}` by the advice (slice test of the handler).

**Testcontainers integration tests**
- **Increment & retrieval:** create analyses → `GET /usage` shows `1/5 … 3/5`; a second user's usage is unaffected (**user isolation**).
- **Blocking:** at `5/5`, the next `POST /api/v1/analyses` → `USAGE_LIMIT_EXCEEDED`, no `Analysis` row created, `used` stays 5.
- **Lifetime allowance:** `DELETE /api/v1/analyses/{id}` does **not** change `used`, and the user is still blocked at the cap.
- **Failure refund:** a failing pipeline → analysis `FAILED` → `used` decremented once; the user can retry and succeed. Triggering the refund twice (or double failure callbacks) still decrements once. A `COMPLETED` analysis is never refunded.
- **SERVICE_BUSY refund:** with a saturated/rejecting executor, the request returns `503 SERVICE_BUSY` and `used` is unchanged afterwards, with no leaked unit.
- **Duplicates:** same URL while the first is `QUEUED/CLONING/…` → same `analysisId` returned, `used` incremented once, one `Analysis` row. Different URL → separate charge. After the first completes or fails, the same URL is a new analysis and a new charge. At `5/5`, resubmitting an in-flight URL returns the existing analysis instead of 403.
- **Concurrency (the important one):**
    - free user at `0/limit`, `N = 3 × limit` parallel POSTs to distinct URLs → exactly `limit` succeed, the rest get `USAGE_LIMIT_EXCEEDED`, `user_usage.used_count == limit`, and `count(analyses) == limit`.
    - free user at `limit - 1`, several parallel POSTs → exactly **one** succeeds.
    - parallel POSTs of the **same** URL → one analysis, one charge.
    - first-ever-request race (no usage row yet) → no unique-constraint error, correct counts.
    - parallel creates racing with failure refunds → final count is consistent (`used == number of charged, non-refunded analyses`).
- **Premium:** a premium user created past the free limit (e.g. `limit + 5` analyses) is never blocked; `GET /usage` shows `limit: null` and the true `used`; failed analyses are still refunded; an expired premium plan falls back to free-tier enforcement via `effectivePlan()`.
- **Existing tests:** all existing analysis/security tests still pass, including `EveryEndpointIsProtectedTest`.

---

## 10. Documentation updates (required)
- **`Architecture.md`**, V0.5 *Usage* section: replace the old `{analysesUsed, analysesLimit, resetAt}` sketch with the new response format, the lifetime-allowance semantics, refund rules, duplicate handling, the premium and downgrade behavior, and the locking strategy.
- **`backend/CLAUDE.md`**: add the `usage/` package to the package tree, list the response DTO, and add a short rule: *"All analysis creation must go through the registrar; never call the pipeline directly."*
- `application.properties`: the new keys with comments.

---

## 11. Suggested order of work
1. Inspect the packages and files listed at the top. Decide the schema approach, error status code and URL-normalization reuse. Note anything surprising.
2. `usage/`: enum, entity, repository (ensure-row, lock, guarded decrement), properties, config binding. Unit tests.
3. `Analysis` columns + the transactional registrar; wire `createAnalysis`. Make sure existing tests still pass.
4. Refund hooks in `AnalysisPipeline` failure paths and the `SERVICE_BUSY` path.
5. Duplicate handling inside the registrar.
6. `UsageLimitExceededException` + advice mapping.
7. `GET /api/v1/usage` + DTOs; security test.
8. Remaining tests (§9), then docs (§10).
9. Run `./mvnw clean test`. Fix everything.

---

## 12. Open points: decide sensibly, then report in the summary
1. **Backfill:** users who already created analyses before this feature start at `used = 0`. (Default: no backfill. Mention it. Don't build a backfill unless trivial and clearly safe.)
2. **Status code:** 403 vs 429 per §6.
3. **Reanalyze / other entry points:** found or not, and how they were wired.
4. **Deleted-while-running** analyses are not refunded (§5.4).
5. Whether `AI_QUERY` appears in the usage response (default: yes, so the frontend is future-proof; remove it from the response if the existing API conventions argue against listing unused features).

---

## 13. Final report (what to output when done)
Summarize:
1. All changes made (files and packages added/changed).
2. The **exact** `GET /api/v1/usage` JSON format (as implemented).
3. Configuration properties added.
4. Database schema changes (tables, columns, constraints, and how they're applied under the project's schema approach).
5. Design decisions (locking approach, status code, duplicate semantics, anything from §12).
6. `./mvnw clean test` results (counts of tests run/passed/failed/skipped), and any known gaps.