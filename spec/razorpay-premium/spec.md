# Spec: Razorpay Premium (payments, renewal reminders, expiry and repository retention)

Location: `spec/razorpay-premium/spec.md` (repository root). Backend first, then frontend.

**Workflow rule for whoever implements this: do NOT commit anything and do not offer or ask to commit. Leave all changes in the working tree; the owner will say explicitly when a commit is wanted.**

Read first: `CLAUDE.md`, `backend/CLAUDE.md`, `frontend/CLAUDE.md`, `Architecture.md` (Usage, Security, User, Ask AI, account deletion), `frontend/DESIGN-NOTES.md`. Existing code this builds on: `user/User` (`plan`, `planExpiresAt`, `effectivePlan(now)`, `changePlan`), `security/EntitlementService`, `usage/UsageService` (`isHeldToFreeLimit`), `analysis/{AnalysisService,AnalysisRegistrar,AnalysisPurgeService,AnalysisCleanup,Analysis}`, `ai/chat/ChatService`, `mail/{EmailService,SmtpEmailService}`, `common/TimeConfig` (one `Clock`), `security/SecurityConfig`, `frontend/src/lib/resetTime.ts`, `features/upgrade/UpgradeProvider.tsx`, `lib/plans.ts`, `features/settings/*`.

## 1. Goal
Let a signed-in user buy Premium through Razorpay (monthly or annual, one-time payment or auto-renewing subscription), show them clearly what Premium adds, warn them before and after it ends, and, when it lapses, keep only their 5 most recently used repositories.

## 2. Decisions (binding)
| Area | Decision |
| --- | --- |
| Prices | Monthly **700 INR** (70000 paise), annual **7000 INR** (700000 paise). Inclusive of taxes. Configurable (`archyra.billing.prices.*`); the client never sends an amount. |
| Payment modes | The user chooses: **one-time payment** (Razorpay Orders + Standard Checkout, buys one month or one year, renew by hand) or **auto-renew** (Razorpay Subscriptions with Plans, recurring mandate). Both periods work in both modes. |
| Payment methods | Standard Checkout shows the methods enabled on the Razorpay account (cards, UPI, netbanking, wallets). Our API has **one create endpoint, one verify endpoint and one webhook per mode**, not one endpoint per method. |
| Source of truth | The **signed webhook** decides whether money arrived. The client-side verify call is a fast path that does the same idempotent work; whichever comes first wins, the second is a no-op. |
| Entitlement | Unchanged mechanism: `users.plan = PREMIUM` and `plan_expires_at`. A paid period moves `plan_expires_at` forward; `effectivePlan` already downgrades at expiry with no job. |
| Extension rule | New expiry = `max(now, current expiry)` + one period (1 month or 1 year as calendar months/years in `archyra.billing.zone`, default `Asia/Kolkata`, via `ZonedDateTime`). Renewing early never loses the days left; renewing late starts from now. |
| One active subscription | While an auto-renew subscription is active, creating another subscription or a one-time order is refused (`409 SUBSCRIPTION_ACTIVE`). To switch, cancel first. |
| Cancelling auto-renew | Cancels at the end of the current cycle; Premium stays until `plan_expires_at`. |
| Reminder | **3 days before** expiry, one email per expiry date, only for users who will NOT auto-renew. If they have more than 5 repositories the email says which ones would be deleted. |
| After expiry | The user is FREE immediately (existing behaviour) and gets an "expired" email stating the deletion date. They keep all repositories for a **3-day grace period**. |
| Deletion | **3 days after expiry** (`archyra.billing.grace-days`), if still not renewed: keep the **5** most recently active repositories (`archyra.billing.keep-repositories`), delete the rest. Skipped entirely when the user has 5 or fewer. |
| "Recently used" | Last activity = the latest of: analysis created or completed, re-analysis, an Ask AI question asked in it, and opening it in the app (throttled, see 3.6). The least recently active repositories are deleted first. FAILED analyses never count toward the 5 and are deleted too (they hold no results). Running analyses are skipped and handled by the next sweep. |
| Credits | Retention deletion does **not** refund credits and does not reduce `used_count` (the existing rule "a downgrade keeps the full count" stands; refunds only happen when the user deletes by choice). |
| Who is covered | Any user with `plan = PREMIUM` and a non-null `plan_expires_at`, including an admin-set expiry. `plan_expires_at = null` (premium without an end) never lapses. |
| Refunds and chargebacks | Recorded (webhook stored, status set to `REFUNDED`) but **do not automatically shorten Premium**; an admin handles it through the admin plan endpoint (6.5). |
| Razorpay not configured | The app starts normally; every billing endpoint except `GET /billing/plans` and `GET /billing/status` returns `503 BILLING_UNAVAILABLE`, and the UI shows "Payments are not available right now". |
| Third-party script | Razorpay's `checkout.js` (https://checkout.razorpay.com/v1/checkout.js) is loaded **only** on the payment screen, lazily, never on other pages. Document this exception in `DESIGN-NOTES.md` next to the "no third-party request" note. |

## 3. Backend

### 3.1 Dependency and configuration
- Add the official `com.razorpay:razorpay-java` SDK (or a plain `RestClient` with HTTP Basic if the SDK conflicts with Boot 4; decide and state why in the report). Never log keys, signatures, payloads or customer data.
- `application.properties` (no YAML), all from the environment, none committed:
  `archyra.billing.razorpay.key-id=${RAZORPAY_KEY_ID:}`, `key-secret=${RAZORPAY_KEY_SECRET:}`, `webhook-secret=${RAZORPAY_WEBHOOK_SECRET:}`, `plan-monthly=${RAZORPAY_PLAN_MONTHLY:}`, `plan-yearly=${RAZORPAY_PLAN_YEARLY:}`;
  `archyra.billing.prices.monthly-paise=70000`, `yearly-paise=700000`, `currency=INR`;
  `archyra.billing.zone=${BILLING_ZONE:Asia/Kolkata}`, `reminder-days=3`, `grace-days=3`, `keep-repositories=5`, `sweep-interval=15m`, `touch-interval=10m`.
- Billing is enabled when the key id and secret are set (the webhook secret and plan ids are required for the webhook and subscription endpoints respectively). Validate the zone and numbers at startup. Update `.env.example`, README and `Architecture.md`.
- Test-mode and live-mode keys, webhook secrets and plan ids are separate in Razorpay; the property names are the same, only the values differ. State this in the README together with the dashboard steps in section 7.

### 3.2 Package and data (`billing/`, a new feature package; update the tree in `backend/CLAUDE.md`)
JPA entities (`ddl-auto=update` creates them; check whether any enum column gets a CHECK constraint and add a startup schema step in the style of `LinkedIdentitySchemaUpgrade` if you later extend an enum):
- `payments`: `id` (UUID, also used as the Razorpay `receipt`), `user_id`, `mode` (ORDER, SUBSCRIPTION), `period` (MONTHLY, YEARLY), `amount_paise`, `currency`, `status` (CREATED, PAID, FAILED, REFUNDED), `razorpay_order_id` (unique, nullable), `razorpay_subscription_id` (nullable), `razorpay_payment_id` (unique, nullable), `paid_at`, `extended_from`, `extended_to`, `created_at`. Plain `user_id`, no cross-feature entity reference.
- `subscriptions`: `id`, `user_id` (unique: one per user), `razorpay_subscription_id` (unique), `razorpay_plan_id`, `period`, `status` (CREATED, AUTHENTICATED, ACTIVE, PENDING, HALTED, CANCELLED, COMPLETED), `cancel_at_cycle_end` (bool), `current_end` (nullable instant from Razorpay), `created_at`, `updated_at`.
- `webhook_events`: `event_id` (unique, from `X-Razorpay-Event-Id`), `type`, `received_at`, `processed_at`. Inserted in the same transaction as the work, so a duplicate delivery is a no-op.
- `billing_notices`: `user_id`, `kind` (REMINDER, EXPIRED, DELETED), `period_end` (the expiry instant the notice is about), `sent_at`; unique `(user_id, kind, period_end)`. This is what makes every email and the enforcement idempotent.
- `analyses.last_activity_at` (nullable instant): see 3.6. A startup step fills nulls with `coalesce(updated_at, created_at)`.

### 3.3 Services
- `RazorpayGateway` (interface) with `createOrder`, `createSubscription`, `cancelSubscription`, `fetchSubscription`, implemented over the SDK/`RestClient`. Everything else depends on the interface, so the tests run against WireMock fakes of the Razorpay API (the same rule as the LLM providers: WireMock fakes only for the external service, everything else real).
- `PaymentSignatures`: pure, constant-time (`MessageDigest.isEqual`) HMAC-SHA256 hex checks:
    - order checkout: `HMAC(key_secret, orderId + "|" + paymentId)`;
    - subscription checkout: `HMAC(key_secret, paymentId + "|" + subscriptionId)`;
    - webhook: `HMAC(webhook_secret, rawBody)` against the `X-Razorpay-Signature` header, on the **raw body bytes** before any JSON parsing.
- `BillingService` (transactional): `applyPayment(...)` is the single place that extends Premium. It locks the user row, flips the payment `CREATED -> PAID` with a guarded UPDATE (so it runs once however many times or from whichever path it is reached), checks that the amount and currency equal what our payment row recorded (a mismatch is ignored, logged without the payload, and never extends Premium), sets `plan = PREMIUM` and the new `plan_expires_at` through `User.changePlan`, stores `extended_from/to`, and after commit sends the "Premium active" email (a mail failure never fails the operation).
- `SubscriptionService`: create (checks no active subscription, picks the plan id for the period, `total_count` 120 monthly or 10 yearly, stores the row), apply webhook events, cancel (Razorpay `cancel(at cycle end)`, then set `cancel_at_cycle_end`).
- `PremiumLifecycleJob` (`@Scheduled`, fixed delay `sweep-interval`, uses the injected `Clock`; every step is idempotent and safe to run twice or concurrently on two nodes through the `billing_notices` unique key and row locks):
    1. **Reminder:** users with `plan = PREMIUM`, `plan_expires_at` between now and now + `reminder-days`, no active (non-cancelled, non-halted) subscription and no REMINDER notice for this `period_end` -> send the reminder, then record the notice.
    2. **Expired:** `plan_expires_at <= now` and no EXPIRED notice for this `period_end` -> send the expired email with the exact deletion date and time, record it.
    3. **Enforcement:** `plan_expires_at + grace-days <= now` and no DELETED notice for this `period_end`. In one transaction: lock the user, **re-read the expiry** (if it moved because they renewed, stop), choose the repositories to delete (3.5), purge them, record DELETED, then set `plan = FREE`, `plan_expires_at = null`. After the commit drop the Neo4j graphs (reuse the `PendingGraphPurge` retry mechanism that account deletion uses) and send the "repositories deleted" email.
       An auto-renew user whose subscription charges successfully moves `plan_expires_at` forward, so none of the steps fire for them. A halted subscription behaves like no subscription: reminders stop being suppressed.

### 3.4 API (`/api/v1/billing`, all authenticated except the webhook)
| Method and path | Purpose |
| --- | --- |
| `GET /billing/plans` | Prices (paise and display), currency, periods, the free and premium limits read from configuration (analysis allowance, weekly Ask AI allowance, `10/min`), `keepRepositories`, `graceDays`, and the Razorpay public `keyId` when billing is enabled. Used by the payment screen so no number is hard-coded. |
| `GET /billing/status` | `{ plan, effectivePlan, planExpiresAt, state: NONE or ACTIVE or EXPIRING or GRACE or LAPSED, autoRenew: { active, period, cancelAtCycleEnd, nextChargeAt }, grace: { endsAt, repositoriesAtRisk } }`. `EXPIRING` = within `reminder-days` of expiry and not auto-renewing; `GRACE` = expired, before deletion. Times are ISO-8601 instants. |
| `GET /billing/retention` | The repositories ranked by last activity: `kept[]` (the 5) and `atRisk[]` (id, repository URL, `lastActivityAt`, status). Used for the warning and Settings; owner only. |
| `GET /billing/payments` | The caller's payment history (period, amount, status, date), newest first, page-limited. |
| `POST /billing/orders` | Body `{ "period": "MONTHLY" or "YEARLY" }`. Creates a Razorpay order for the server-side amount, stores a CREATED payment, returns `{ orderId, amount, currency, keyId, paymentId }`. |
| `POST /billing/orders/verify` | Body `{ razorpayOrderId, razorpayPaymentId, razorpaySignature }`. Checks the signature, that the order belongs to the caller, then `applyPayment`. Returns the new `status`. Wrong signature: `400 PAYMENT_VERIFICATION_FAILED` and a rate-limit count. |
| `POST /billing/subscriptions` | Body `{ "period" }`. Creates the subscription, returns `{ subscriptionId, keyId }`. `409 SUBSCRIPTION_ACTIVE` if one exists. |
| `POST /billing/subscriptions/verify` | Body `{ razorpaySubscriptionId, razorpayPaymentId, razorpaySignature }`. Verifies and marks the subscription AUTHENTICATED/ACTIVE; the first charge is applied from the webhook or here, idempotently. |
| `POST /billing/subscriptions/cancel` | Cancels at cycle end; `409 NO_ACTIVE_SUBSCRIPTION` when none. |
| `POST /billing/webhook` | Public (no JWT, **CSRF-exempt**, add it to `SecurityConfig` explicitly), body size limited, signature checked first (`400` without a valid signature, nothing else is read), dedupe by `X-Razorpay-Event-Id`, respond `200` within seconds (Razorpay waits about 5 seconds and retries for 24 hours); unknown event types are acknowledged and ignored. |

Webhook events to handle: `payment.captured` and `order.paid` (apply the order payment), `payment.failed` (mark FAILED), `subscription.authenticated`, `subscription.activated`, `subscription.charged` (apply the payment: extend by the subscription's period, idempotent on the payment id), `subscription.pending` and `subscription.halted` (status only), `subscription.cancelled`, `subscription.completed`, `refund.processed` (record only, see decisions). Subscription events are matched to our user through the `razorpay_subscription_id` row, never through anything in the payload alone. Orders carry our `paymentId` in the Razorpay `notes` and `receipt`; never put an email or name in `notes`.
- Errors added to `GlobalExceptionHandler` and the API tables in `Architecture.md`: `BILLING_UNAVAILABLE` (503), `PAYMENT_VERIFICATION_FAILED` (400), `SUBSCRIPTION_ACTIVE` (409), `NO_ACTIVE_SUBSCRIPTION` (409), `INVALID_PERIOD` (400).
- Rate limits (existing `RateLimiter`): order and subscription creation per user, verify per user, webhook per IP (generous, since Razorpay retries).

### 3.5 Choosing what to delete (`AnalysisRetention`, in `analysis/`, public API for `billing/`)
`List<UUID> selectForRetention(UUID userId, int keep)`: all finished analyses (COMPLETED, plus NOT_ANALYZED if that status exists in this codebase) ranked by `last_activity_at` descending, ties by `created_at` descending; keep the first `keep`; return the rest plus all FAILED ones; leave running analyses alone. Deleting uses a new purge path in `AnalysisPurgeService` for a selected set that never refunds (the account-deletion purge already has this behaviour; reuse its internals and keep the "only the registrar changes `user_usage`" rule). Conversations, the AI index and chunks go with the analysis. `GET /billing/retention` uses the same selection so the warning and the deletion can never disagree.

### 3.6 Activity tracking
Add `AnalysisActivity.touch(UUID analysisId)` (public, in `analysis/`): a guarded UPDATE `set last_activity_at = :now where id = :id and (last_activity_at is null or last_activity_at < :now - touch-interval)` so it writes at most once per interval. Call it: when an analysis is created and when it completes; from `ChatService` for every question that is answered (including free cache hits); and when the user opens a repository (from `AnalysisService.requireCompleted`, the ownership choke point every result endpoint already uses). Never touch on a failed ownership check. Add the column to Neo4j nothing; this is PostgreSQL only.

### 3.7 Emails (`EmailService` gains four methods, Thymeleaf templates next to the existing ones, HTML plus text; sent after commit, async, failures logged without the address)
- **Premium active:** plan period, amount, `planExpiresAt` in exact date and time with the zone name (for example "Friday 27 November 2026, 11:58 IST"), auto-renew or not.
- **Reminder (3 days before):** the exact end date and time, a Renew button linking to the payment screen, and, only when the user has more than 5 repositories, the sentence that on `<exact deletion date and time>` only the 5 most recently used repositories are kept, followed by the names of those that would be deleted (cap the list at 10 and say "and N more").
- **Expired:** Premium ended at `<exact time>`; renew by `<deletion date and time>` to keep everything (the repositories at risk, as above, only when there are more than 5).
- **Repositories deleted:** how many were deleted, which 5 were kept.
  Date formatting for emails uses the billing zone with a zone label. Update the account-deletion notice only if it mentions plans.

### 3.8 Security
- Billing endpoints require a verified, enabled account like the rest of the API; the webhook is the only public one. The webhook never reveals why it rejected a request beyond `400`.
- Compare signatures in constant time; reject a missing or malformed signature before parsing; never log raw bodies or headers.
- Amounts and plan ids come only from configuration; the order's amount is compared with our row at fulfilment.
- A payment for a user who has since been deleted is recorded and ignored (log the payment id only).
- Account deletion interplay: premium is not preserved by the usage ledger (existing rule); deleting an account with an active Razorpay subscription must cancel it at Razorpay (best effort, after commit, never fails the deletion; log without identifiers).

## 4. Frontend

### 4.1 Payment screen (route `/upgrade`, linked from the app shell, Settings, the existing upgrade dialog and every "Upgrade to Premium" mention such as the Ask AI out-of-credits panel)
- Header with the two periods as a segmented control (Monthly 700 INR, Annual 7000 INR, with the annual saving computed from the server numbers) and a second control for **One-time payment / Auto-renew** with one honest sentence each (what renews, how to cancel).
- **Free vs Premium comparison**, built from `GET /billing/plans` and the existing `PLAN_ROWS`: repository analysis allowance, Ask AI per week against unlimited (with the 10 per minute fair-use line), the graph features Premium unlocks today (only list a feature as included if the backend serves it; keep the "Coming soon" honesty of `PlanComparisonTable`), and a row "If Premium ends": Free keeps only your 5 most recently used repositories after a 3-day grace period.
- One primary button "Pay 700 INR" (or 7000 INR / "Start auto-renew") that: creates the order or subscription, loads `checkout.js` lazily, opens Razorpay Checkout with the returned ids and key, prefilled with the user's email and name, then on success calls the verify endpoint and refetches status. Dismissing the checkout is not an error. `payment.failed` shows the reason Razorpay gives. After a success the screen polls `GET /billing/status` for up to 30 seconds (the webhook may be the first to land) and then shows "Premium is active until `<exact date and time>`".
- Do **not** pass a `theme.color` to Checkout (the token check forbids colour literals in `src/`); the default is fine.
- Already Premium: the screen shows the current end date and a Renew / Add a period action, and, for auto-renew users, a Cancel auto-renew action with a confirm dialog that states the date Premium then ends.
- `BILLING_UNAVAILABLE` shows a calm unavailable state with no checkout button.

### 4.2 Expiry notices
- **Time wording uses the same helper as the Ask AI reset message.** Generalise `lib/resetTime.ts` without changing `resetWait` or its tests: add an exported `timeUntil(iso, now?, timeZone?)` that returns the same `{text, kind, due, msUntil}` ("till 05:30" under 24 hours, whole days rounded up otherwise), and make `resetWait` call it. Copy: `Your Premium ends in 3 days` / `Your Premium ends today at 05:30` or `tomorrow at 05:30` when the clock time falls on the next local day.
- **Banner** in the app shell on every page, while `state` is `EXPIRING` or `GRACE`: EXPIRING says when Premium ends and offers Renew; GRACE says Premium ended, how long until deletion (same helper) and how many repositories will be deleted, offers Renew and "See what will be kept". Not dismissible while the state lasts. Uses existing notice and button components and tokens only.
- **Dialog after every sign-in:** shown once right after each interactive sign-in (email, Google, GitHub), not on a silent session refresh. Set a one-shot `sessionStorage` flag in the sign-in completion path, which is the existing session-boot hydration path (find where the sign-in finishes; do not add a second hydration path, and never use `queryClient.clear()` and rely on a refetch); the dialog reads and clears it once. It shows the same message, the repositories that would be kept (5) and at risk, and Renew / Later. Not shown for FREE users who never had Premium, or when `state` is `NONE` or `ACTIVE`.
- **Settings, new "Subscription" section:** the plan, the **exact end date and time** always shown for a Premium user, in the user's local time zone with the zone name (for example "Ends on Friday, 27 November 2026 at 11:58 (IST)"; "Ended on ..." after expiry; "No end date" when `plan_expires_at` is null), auto-renew status and next charge date, Renew / Manage actions, the payment history table, and the retention list (`kept` and `atRisk`) with each repository's last-used time.
- The Usage view shows the plan and end date in one line for Premium users.

### 4.3 Wiring
New hooks only (`hooks/useBilling.ts`: plans, status, retention, payments, createOrder, verifyOrder, createSubscription, verifySubscription, cancelSubscription, `useRazorpayCheckout`). Query keys in `api/queryKeys.ts`; after any successful billing mutation invalidate status, usage and the current user. Errors by `code` in `lib/errorCopy.ts` with the unit test updated. Add the types to `api/types.ts`. No mocks, tokens only, correct in Light and Dark, accessible (the checkout button states, live region for the processing state, focus management for dialogs, no colour-only signals), fixed app type scale.

## 5. Tests
Backend (real PostgreSQL and Neo4j, GreenMail; the Razorpay API replaced by WireMock fakes behind `RazorpayGateway`; a movable `Clock`; real HMACs with test secrets):
1. Signatures: valid and invalid order, subscription and webhook signatures; tampered body; missing header; constant-time compare helper.
2. Order flow: create (amount from config, never from the client), verify extends Premium by one month and by one year; renewal before expiry keeps the remaining days; renewal after expiry starts from now; `Asia/Kolkata` month-end and leap-year cases.
3. Idempotency: the same payment applied by verify and then by `payment.captured` and `order.paid` webhooks, and delivered twice, extends exactly once; a replayed event id is a no-op; concurrent verify and webhook for one payment extend once.
4. Wrong owner, wrong amount, wrong currency, unknown order, unconfigured billing (`503`), invalid period.
5. Subscription flow: create, verify, `subscription.charged` extends each cycle once, `halted` and `pending` update status only, cancel at cycle end keeps Premium until expiry, a second subscription or order while active is `409`, subscription events for an unknown subscription are ignored.
6. Webhook endpoint: no JWT and no CSRF needed, bad signature gets `400` and no side effects, oversize body rejected, unknown events acknowledged.
7. Lifecycle with the `Clock`: reminder exactly once per expiry at 3 days before; not sent for FREE, for null expiry, or for an active auto-renew subscription; sent again for the next period; the expired email exactly once; renewing resets everything; no emails for users with 5 or fewer repositories mention deletion.
8. Enforcement at expiry + 3 days: a user with 12 repositories keeps the 5 most recently active (activity from creation, re-analysis, Ask AI question and opening each move a repository up), the other 7 and all FAILED ones are deleted in PostgreSQL and Neo4j including conversations and the AI index, `used_count` is unchanged (no refund), plan becomes FREE, DELETED email sent; a running analysis is skipped; 5 or fewer repositories deletes nothing; renewing during the grace period prevents it; a renewal landing between selection and deletion is caught by the re-read under lock; the job running twice or on two threads deletes once.
9. `GET /billing/retention` and the deletion choose the same set; `GET /billing/status` states `EXPIRING`, `GRACE`, `NONE`, `ACTIVE` correctly at the boundaries.
10. Activity throttling: `touch` writes at most once per interval; an unauthorised open never touches.
11. Account deletion with an active subscription cancels it at Razorpay and still deletes the account if Razorpay is down.
12. No log line contains a key, signature, payload, email or payment details (assert on captured logs for the failure paths).
13. All existing suites stay green; `./mvnw clean test` passes.

Frontend: unit tests for `timeUntil` (same results as `resetWait`, including exactly 24 hours, under a minute, a day change, another time zone), the date-time formatter with zone name, the notice copy, the sign-in flag logic, the price and saving calculation, and the error copy. E2E (`scripts/e2e/billing.spec.ts`): the payment screen renders the real plan numbers and comparison; period and mode toggles change the button; opening Checkout loads the script and shows the Razorpay frame when the environment has test keys (skip with a clear message otherwise); banner, sign-in dialog and the Settings exact end time for a user whose plan row is seeded directly in the database through the existing e2e database helper (no test-only endpoint in production code); the Cancel auto-renew dialog. A manual Razorpay **test-mode** checklist goes in the README: test card, UPI success and failure, a subscription's first charge and a halt.

## 6. Phases (finish and verify each before the next)
1. Config, entities, `PaymentSignatures`, `RazorpayGateway` with WireMock tests.
2. Orders, verify, `applyPayment`, webhook, status and plans endpoints, payments history.
3. Subscriptions (create, verify, events, cancel) and the billing/account-deletion interplay.
4. Activity tracking, retention selection, lifecycle job and the four emails.
5. Frontend: payment screen, checkout hook, expiry banner and sign-in dialog, Settings section, time helper.
6. Admin plan endpoint `PUT /api/v1/admin/users/{id}/plan` (`{ plan, expiresAt }`, ADMIN only, audit-logged, same last-admin and rules style as the other admin endpoints) so refunds and manual grants have a tool; no admin UI is needed.
7. Docs and the full verification run.

## 7. Razorpay dashboard setup (document in the README; the owner does this, not the code)
Create separate Test and Live API keys; enable auto-capture; create two Plans (monthly: period `monthly`, interval 1, 70000 paise INR; yearly: period `yearly`, interval 1, 700000 paise INR) and put their ids in `RAZORPAY_PLAN_MONTHLY` and `RAZORPAY_PLAN_YEARLY`; create a webhook pointing at `https://<host>/api/v1/billing/webhook` (public HTTPS; use a tunnel in development) with a secret and the events listed in 3.4; confirm which payment methods are enabled on the account. Test and Live have separate webhooks, secrets, keys and plans. Live mode also needs Razorpay's activation, and Subscriptions and recurring methods must be enabled on the account.

## 8. Acceptance criteria
1. A user can pay 700 INR monthly or 7000 INR annually, by one-time payment or auto-renew, through Razorpay Checkout; Premium starts or extends exactly once per payment however the confirmation arrives.
2. The payment screen shows the real difference between Free and Premium from server numbers, including what happens to repositories when Premium ends.
3. Three days before the end, a non-auto-renewing user gets a reminder with the exact end date and time and the repositories at risk; they see the same warning on every sign-in (dialog) and on every page (banner), worded with the same time format as the Ask AI reset message.
4. Settings always shows the exact end date and time (or that there is none).
5. At expiry the user is FREE; after the 3-day grace period without renewal only the 5 most recently used repositories remain, the rest are deleted everywhere without refunding credits; users with 5 or fewer lose nothing.
6. No amount is client-controlled; webhooks are signature-verified and deduplicated; no secret, signature or payload is logged.
7. No regression of usage limits, the usage ledger, account deletion, the dark theme, the weekly Ask AI allowance or the existing suites; `./mvnw clean test` and `npm run verify` pass; `Architecture.md`, `backend/CLAUDE.md`, `frontend/CLAUDE.md`, README and `.env.example` are updated.

## 9. Workflow
**Do not commit anything and do not offer or ask to commit.** Leave every change in the working tree and report what changed, what was verified, and what could not be (for example live Razorpay calls, which need real keys).