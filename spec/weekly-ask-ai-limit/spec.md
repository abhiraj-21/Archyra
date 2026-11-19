# Weekly Ask AI allowance

Status: built (2026-11-20). Design record: `Architecture.md` → "Decision: Ask AI questions are a weekly allowance". The decisions below were given by the owner and are **binding**.

## Decisions

1. **Scope.** Only `AI_QUERY` is weekly. Repository analysis stays a lifetime allowance, unchanged. PREMIUM is still counted and never blocked. The 10 per minute rate limit, free cache hits, reserve-before-call and refund-exactly-once stay.
2. **Limit.** `archyra.usage.free-tier.ai-query-limit=${AI_LIMIT:15}` (default drops from 20 to 15).
3. **Reset moment.** Every Monday 00:00 in `archyra.usage.reset-zone=${USAGE_RESET_ZONE:Asia/Kolkata}`, validated at startup (an unknown zone fails). The week is Monday 00:00 inclusive to the next Monday 00:00 exclusive, computed with `ZonedDateTime` (DST-safe), not `+ 7 * 24h`.
4. **Mechanism.** Lazy, no scheduler, no cron. `user_usage.window_start` (nullable `Instant`, weekly types only). `lock`/`charge` (and reads) compare it with the current window start from an injected `Clock` and roll the row over (`used_count = 0`, `window_start = current`) in the same locked transaction. `lock`, `charge`, `release` stay `MANDATORY`-transactional with their locking.
5. **Existing data.** Rows with a null `window_start` roll over to 0 on first use; nobody inherits a lifetime count. `ddl-auto=update` adds the column.
6. **Refunds.** A refund only decrements the row if the unit was reserved in the current window; after a rollover it is a no-op (never negative, never credits the new week). The reservation stores its window (`chat_messages.quota_window_start`); the exactly-once guard stays.
7. **`GET /usage`.** The `AI_QUERY` entry adds `resetsAt` (next Monday 00:00 in the zone); lifetime entries have none. PREMIUM may have it, nothing is blocked. The Ask AI `403 USAGE_LIMIT_EXCEEDED` body has `resetsAt` as well.
8. **Ledger.** The ledger entry for `AI_QUERY` stores `window_start` with the count; a restore takes the count only when the stored window is the current one (older: 0). Lifetime types unchanged.
9. **Frontend message.** When the limit is reached: "Out of credits. Upgrade to Premium or wait till <when>." with `<when>` computed from `resetsAt` in the browser: under 24 hours away, the local `hh:mm` (24-hour, `Intl`); otherwise whole days rounded up ("wait 3 days", "wait 1 day"). "Upgrade to Premium" keeps the existing upgrade action. One pure helper (`lib/resetTime.ts`) with unit tests. The count and the panel refresh when the reset passes (refetch `/usage` at that time and on focus; no polling), so the composer unlocks without a reload.
10. **Weekly copy.** Ask AI counting copy and guide say "15 free questions per week" (from the usage entry, never hard-coded); the usage view labels the AI row "per week, resets Monday".
11. **Error copy** goes through `lib/errorCopy.ts` by `code`.

## Where it lives

Backend: `usage/UsageWindows`, `UsageService`, `UserUsage`, `UserUsageRepository`, `UsageLedgerService`/`Repository`/`Entry`, `UsageProperties`, `UsageEntry`, `common/UsageLimitExceededException`, `ErrorResponse`, `ai/chat/ChatTransactions`/`ChatMessage`. Frontend: `lib/resetTime.ts`, `hooks/useRefetchWhenDue.ts`, `hooks/useAccount.ts` (`useAiUsage`), `features/repository/ask-ai/LimitPanel.tsx`/`ChatWorkspace.tsx`, `features/usage/AiUsageMeter.tsx`, `lib/errorCopy.ts`, `lib/askAi.ts`, `api/errors.ts`.

## Differences from the letter of the decisions

- **`release` does not roll the row over** (decision 4 lists release among the operations that roll over). It does not need to: a refund for an earlier window is a no-op decided from the reservation's stored window, and a refund for the current window finds the row already in it (the charge put it there). The guarded `UPDATE` also matches `window_start`, so a row that moved on is never touched.
- **A read (`GET /usage`) does not persist the rollover**: it takes no lock and creates nothing (an existing contract), so it computes "0 used" for an old window and leaves the row for the next `lock`/`charge`.
- **The clock time uses `hourCycle: 'h23'` with a pinned `en-GB` locale**, not `hour12: false`: that option prints midnight as "24:05" in some engines.

## Tests

Backend (real PostgreSQL, a movable `Clock`, no sleeping): `UsageWindowsTest` (Monday boundaries, last second, DST spring forward and fall back, Kolkata/Auckland/London, year boundary), `UsagePropertiesTest` (zone validation), `UsageServiceTest`, `WeeklyUsageIntegrationTest` (16th refused with `resetsAt` and 0 after Monday, refunds across the reset, concurrent charges at the rollover, legacy null window, schema update over a table without the column, analyses untouched, PREMIUM, downgrade, `resetsAt` only on the AI row), `AccountDeletionIntegrationTest` (ledger: same week still at the limit, after Monday zero, legacy ledger entries, newer replaces older), `ChatQuotaIntegrationTest` (403 body, stored window, refund of an earlier week's unit). Frontend: `resetTime.test.ts`, `errorCopy.test.ts`, `LimitPanel.test.tsx`, `useRefetchWhenDue.test.tsx`, `askAi.test.ts`, `QuestionGuide.test.tsx`; e2e in `scripts/e2e/ask-ai.spec.ts` (injected 403 with `resetsAt`; the real exhaustion flow is opt-in with `ARCHYRA_E2E_AI_EXHAUST=1` because it spends provider quota).
