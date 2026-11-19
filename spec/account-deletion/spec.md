# Spec: Delete account, without resetting usage limits

Location: `spec/account-deletion/spec.md` (repository root). Backend first, then frontend.

Read first: `CLAUDE.md`, `Architecture.md` (User, Linked sign-in methods, Change password, Usage, Ask AI), `backend/CLAUDE.md` (usage rules, `AnalysisRegistrar` is the only way to delete analyses, one-time tokens, audit log, "emails after commit never fail the operation", generic 202 on register), `frontend/CLAUDE.md` (Settings, error copy by `code`, session boot).

## 1. The problem
Deleting a user today would delete `user_usage`, so a user who has used all of their free analyses and AI questions could delete the account, register again and start from zero, repeatedly spending our API keys. Deleting an account must remove the person's data but **keep their consumption**, and it must come back when the same person returns.

## 2. Decisions (binding)
| Area | Decision |
| --- | --- |
| What is deleted | The user row, linked identities, refresh tokens, one-time tokens, OAuth link intents, all analyses with their Neo4j graph, metadata, AI index, chunks, conversations and messages. |
| What is kept | A **usage ledger**: for each identity key, the per-type `used_count` values. No email, no name, no repository URL, no content. |
| Identity keys | `HMAC-SHA256(secret, value)` of (a) the normalised email, (b) each `provider:provider_user_id` of every linked identity. Secret = new property `archyra.usage.ledger-secret` from env `USAGE_LEDGER_SECRET` (required, at least 32 bytes, fail startup if missing or weak; document in README and `.env.example`). Raw values are never stored or logged. |
| Email normalisation | Trim, lower-case. For `gmail.com` and `googlemail.com` also drop dots in the local part and everything from `+`. For all other domains drop only a `+tag` suffix. Registration uses the same function so `a+1@x.com` and `a@x.com` hit the same key. |
| Restore | On **any** creation of a user (email registration, Google or GitHub first sign-in, and when a social identity is linked to an existing account), look up all that user's keys, take the **maximum** `used_count` per usage type found, and write it into the new `user_usage` row. Counts only go up. |
| Linking | Linking a second identity to an existing account also merges: take the max between the account's current counts and the ledger entries for the new identity's key. This stops "delete, then sign up with a different email, then link the old Google identity". |
| Refunds | Deleting analyses as part of account deletion **never refunds credits**. Use a purge path that skips refund logic and guard it with a test. |
| Plans | The ledger stores counts, not the plan. A returning user always gets the FREE plan with restored counts. PREMIUM is not preserved across deletion (state this in the UI text). |
| Retention | The ledger is kept indefinitely (anti-abuse). It is a small, non-reversible record; disclose it in the confirm dialog and in `README`/`Architecture.md`. No admin UI needed. |
| Privacy of re-registration | Registration stays the generic `202`; the restore is silent and is never mentioned in any response or email. |
| Admins | An ADMIN cannot delete themselves while they are the last enabled admin (`409 LAST_ADMIN`, existing code). |
| Auth for the action | Password account: current password required. OAuth-only account (`hasPassword = false`): require a fresh emailed confirmation link (one-time token of a new purpose `ACCOUNT_DELETE`, valid 15 minutes, single use) because there is no password to ask for. |

## 3. Backend

### 3.1 Schema (JPA entities; `ddl-auto=update` creates the tables)
- `usage_ledger` (`id`, `identity_key` unique `varchar(64)`, `usage_type`, `used_count` int, `updated_at`; unique `(identity_key, usage_type)`). Package `usage/`.
- Extend the one-time token purpose enum with `ACCOUNT_DELETE`; add `GET`-free confirm flow below.

### 3.2 Service (`usage/UsageLedgerService`, called by `user/AccountDeletionService`)
- `Set<String> keysFor(User)`: the email key plus one key per linked identity.
- `void record(User)`: inside the deletion transaction, upsert each usage type's `used_count` for every key (keep the max on conflict).
- `Map<UsageType,Integer> restoreFor(Collection<String> keys)`: max per type.
- `UsageService` gains `seedOnCreate(userId, Map<UsageType,Integer>)` (MANDATORY transaction, only raises counts). `UserService`/`SocialAccountResolver` call restore when they create a user; the link flow calls the merge. Nothing else touches `user_usage` directly.
- Add the new keys to `User` creation paths **in one transaction** so there is no window with zero counts.

### 3.3 Deletion (`user/AccountDeletionService`, `@Transactional`)
Order: authorise → `record(User)` (ledger first) → purge analyses through `AnalysisRegistrar` with a `purge` method that deletes graph/metadata/index/conversations and **does not refund** → delete refresh tokens, one-time tokens, intents, linked identities, `user_usage` → delete the user. Neo4j deletion is outside the DB transaction: if graph cleanup fails, the account deletion must still succeed and the orphaned `analysisId`s are logged (ids only) and handled by the existing orphan cleanup, if any; otherwise add a small retry-safe `PendingGraphPurge` record. After commit: revoke nothing else is needed (rows are gone), clear the refresh cookie, send a "your account was deleted" email (never fails the operation, to the address held before deletion), write an audit line (`userId`, no email). Idempotent for a second call (401 after the token family is gone).

### 3.4 API
- `POST /api/v1/users/me/delete` body `{ "password": "…" }` for password accounts → `204`, clears the cookie. Wrong password: `401 INVALID_CREDENTIALS` and count it toward the lockout exactly like change-password.
- `POST /api/v1/users/me/delete-request` (OAuth-only accounts) → `202`, emails a confirmation link to the verified address; `POST /api/v1/users/me/delete-confirm` body `{ "token": "…" }` → `204`. The frontend page for the link is `/settings/delete-confirm?token=…`.
- Using the wrong flow for the account type: `400 DELETE_METHOD_MISMATCH`. Last admin: `409 LAST_ADMIN`. Rate limit the three endpoints with the existing auth buckets.
- Add the endpoints to `SecurityConfig` (authenticated), `GlobalExceptionHandler`, and the API table in `Architecture.md`.

### 3.5 Hardening
- Registration, login and OAuth paths must never reveal that a ledger entry exists. Add a test that responses are byte-identical in shape for a ledger match and no match.
- Never log the secret, raw keys, emails alongside user ids in deletion logs, or key hashes.
- A deleted email can register again immediately (no cooldown), with restored counts.

### 3.6 Backend tests (real PostgreSQL, Neo4j, GreenMail)
1. Exhaust FREE analyses and AI questions, delete (password flow), register the same email: counts are exactly the old counts and further use is refused (`403 USAGE_LIMIT_REACHED` / the existing codes).
2. Same through `a.b+x@gmail.com` variants and a different case.
3. Delete, then register with a **different email**, then link the old Google identity: counts restored by the merge.
4. Delete a Google-only account (confirmation-link flow), sign in with Google again: counts restored.
5. A never-used account deletes and returns with zero counts; ledger stores no zero rows beyond what is needed.
6. Analyses of the deleted account are gone in PostgreSQL and Neo4j and **no credit is refunded** (assert the ledger equals `used_count` before deletion).
7. Wrong password, last admin, wrong flow, expired or reused delete token, token for another user.
8. Counts never decrease on restore (max rule) and a PREMIUM user returns as FREE.
9. Registration response identical with and without a ledger match; startup fails without a valid `USAGE_LEDGER_SECRET`.
10. Existing suites stay green.

## 4. Frontend (`features/settings`)
- A **Danger zone** section at the bottom of Settings with **Delete account**. It opens a confirm dialog (focus-trapped, Escape closes) that says: your repositories, graphs and Ask AI conversations are deleted permanently; **your usage is not reset, so creating a new account later keeps the analyses and questions already used**; paid plans are not carried over. Require typing the word `DELETE` and, for password accounts, the password. OAuth-only accounts see "We'll email you a confirmation link" and a sent state; the link page `/settings/delete-confirm` completes it.
- Hook `useDeleteAccount` (and `useRequestAccountDeletion`, `useConfirmAccountDeletion`); after success clear the session through the existing session path, navigate to the landing page with a short "Account deleted" notice. Do not call `queryClient.clear()` and rely on a refetch (see the hydration rule in `frontend/CLAUDE.md`).
- Errors by `code` in `lib/errorCopy.ts` (`INVALID_CREDENTIALS`, `LAST_ADMIN`, `DELETE_METHOD_MISMATCH`, expired token) with unit tests. Tokens only, accessible, no mocks.
- E2E: a throwaway account deletes itself with its password; signing in fails; registering again shows the previous usage on the Usage view; the `DELETE` confirmation gates the button.
- Update `frontend/CLAUDE.md` and `Architecture.md`.

## 5. Acceptance criteria
1. A user at their limit who deletes the account and registers again (same email, a plus or dot variant, or the same Google/GitHub identity) is still at their limit.
2. All personal data and repositories are removed; only hashed keys and counts remain.
3. OAuth-only users can delete through an emailed confirmation.
4. No response, email or log reveals the ledger.
5. `./mvnw clean test` and `npm run verify` pass; docs updated, including `USAGE_LEDGER_SECRET` in README and `.env.example`.

## 6. Workflow
Commit per part. **Commit messages must not contain `Co-Authored-By`, a co-author of any kind, or any "Generated with" / Claude / Anthropic line.**