# Spec: Ask AI (V1), frontend: the Ask AI tab

Location: `spec/ask-ai-frontend/spec.md` (repository root). **Prerequisite: `spec/ask-ai-backend/spec.md` is implemented, green and running.** This spec is built against the real `/api/v1` backend only. If the backend lacks something this spec needs, report it; never invent data (`frontend/CLAUDE.md`: no mocks, ever).

Read first: `CLAUDE.md`, `frontend/CLAUDE.md` (every rule applies, in particular: components use hooks only, one query-key file, errors by `code` through `lib/errorCopy.ts`, Hallmark "Coral" tokens only, `Blueprint` aesthetics banned, `npm run check:tokens`, accessibility), `frontend/DESIGN-NOTES.md`, `frontend/spec/ui/spec-ui.md`, `Architecture.md` (Ask AI, Usage, the new `ai-index` and conversations sections), and the backend spec (API contract, error codes). Do not regress `spec/graph-controls-and-auth-fixes` or `spec/oauth-login-hydration-and-account-linking` (session boot, scope control, dragging, sign-in methods).

## 1. Goal

Replace the "coming soon" Ask AI tab (`features/repository/ask-ai/AskAiPage.tsx`) with a working, repository-aware chat: conversations per analysis, grounded sources, clear index and quota states, and honest error handling. Desktop-first like the rest of the app.

## 2. Decisions (binding)

- Many conversations per analysis, each multi-turn; list, create, delete (no rename). The first question titles the conversation automatically.
- One JSON response per question (no streaming): show a thinking state until it arrives.
- A source is a chip with a type glyph and name; it expands to `file:line–endLine` and the code excerpt, and has an **Open in graph** link.
- Chat is disabled until the index is `READY`. Old analyses say "Re-analyze to enable Ask AI". A failed index has a free **Retry**.
- FREE users have 20 questions for life (`GET /usage`, `AI_QUERY`); PREMIUM is unlimited; everyone is limited to 10 questions per minute.
- Re-analyzing deletes the replaced analysis's conversations; the re-analyze confirmation must say so.
- Free-tier data-use disclosure sits next to the composer.

## 3. API surface used (from the backend spec; confirm shapes with `node scripts/e2e/probe-api.mjs`)

| Call | Used for |
| --- | --- |
| `GET /analyses/{id}/ai-index` | index state; polled every 2 s while `INDEXING`, otherwise fetched once and on demand |
| `POST /analyses/{id}/ai-index` | Retry for `FAILED` with `retryable: true` |
| `GET /analyses/{id}/conversations` | conversation list |
| `POST /analyses/{id}/conversations` | New conversation |
| `DELETE /conversations/{id}` | delete |
| `GET /conversations/{id}/messages` | history |
| `POST /analyses/{id}/chat` | ask: `{ message, conversationId? }` → `{ conversationId, messageId, answer, sources[], cached, provider }` |
| `GET /usage` | the `AI_QUERY` meter |

Error codes handled (by `code`): `USAGE_LIMIT_EXCEEDED`, `RATE_LIMITED` (with `Retry-After`), `AI_NOT_READY`, `AI_UNAVAILABLE`, `AI_REANALYZE_REQUIRED`, `CONVERSATION_NOT_FOUND`, `CONVERSATION_FULL`, `CONVERSATION_BUSY`, `TOO_MANY_CONVERSATIONS`, `ANALYSIS_NOT_COMPLETED`, `VALIDATION_ERROR`, `AUTHENTICATION_REQUIRED` (existing single-flight refresh path).

## 4. Structure and conventions

Follow `frontend/CLAUDE.md` exactly:

- `api/endpoints/ai.ts` (transport only), types in `api/types.ts` (hand-written from the DTOs), keys in `api/queryKeys.ts`.
- Hooks in `hooks/useAskAi.ts`: `useAiIndex` (polling while `INDEXING`), `useRetryAiIndex`, `useConversations`, `useCreateConversation`, `useDeleteConversation`, `useConversationMessages`, `useAskQuestion`. Components never import `fetch`, `api/http` or `api/endpoints/*` (ESLint enforces it).
- Components under `features/repository/ask-ai/`: `AskAiPage` (layout and state switch), `IndexPanel` (not indexed / indexing / failed / empty), `ConversationList`, `ChatThread`, `MessageView`, `SourceChip`, `Composer`, `LimitPanel`. Split further only if a file grows large.
- Pure helpers in `lib/askAi.ts` with unit tests: citation marker parsing, the safe Markdown renderer's block/inline parsing, source label formatting, `Retry-After` handling.
- Error copy for every code above in `lib/errorCopy.ts` (messages say what happened and what to do; never render raw API strings as HTML; unknown codes get generic copy).
- Update `WorkspaceLayout.tsx` (remove the `ComingSoonBadge` on the Ask AI tab), `lib/plans.ts` (Ask AI is now available: `now: true`, copy "20 questions" for free, "unlimited" for premium, taken from the usage response where it is shown, never a literal limit), `LandingPage.tsx` (the Ask AI mention no longer says "coming soon"), and `frontend/CLAUDE.md` (Status, Structure, a short Ask AI section).

## 5. Behaviour and states

The tab opens `useAiIndex(analysisId)` once the workspace is ready, then switches on `status`:

1. **`NOT_INDEXED`**: panel "Ask AI isn't enabled for this analysis yet. Re-analyze the repository to enable it." with a **Re-analyze** button that opens the existing re-analyze flow (reuse `AnalysisActions`' confirm dialog and mutation; do not duplicate it). No chat UI.
2. **`INDEXING`**: panel "Preparing Ask AI for this repository…" with progress from `embeddedCount / chunkCount` (an indeterminate bar while `chunkCount` is 0), `aria-live="polite"`, composer visible but disabled with a reason. Polling stops at `READY`, `FAILED` or `EMPTY`.
3. **`FAILED`**: the safe `reason`; **Retry** (free, no quota) when `retryable`, else a **Re-analyze** button. After Retry the status returns to `INDEXING`.
4. **`EMPTY`**: "This repository has no non-test code to ask about." No chat UI.
5. **`READY`**: the chat workspace below. A `reason` on a ready index (partial coverage note) shows as a small inline note.

### Chat workspace (`READY`)
- Layout: a conversation list on the left (collapsible on narrow widths), the thread, and the composer at the bottom. The thread scrolls; new messages keep the view pinned to the bottom unless the user scrolled up.
- **Conversations:** ordered by recent activity; **New conversation** (creates through the API; at `TOO_MANY_CONVERSATIONS` show the copy asking to delete one); delete asks for confirmation (reuse `Dialog`) and, if the open conversation is deleted, selects the next or the empty state. The selected conversation is in the URL (`?c=<conversationId>`), left out when none, so reload and deep links work; an unknown or deleted id falls back to the empty state (`CONVERSATION_NOT_FOUND`).
- **Empty conversation:** the five example questions from `Architecture.md` as buttons that fill the composer (not send).
- **Asking:** Enter sends, Shift+Enter inserts a newline, the message is limited to 2,000 characters with a visible counter near the limit. On send: show the user message at once, then an assistant "Thinking…" placeholder (`aria-live="polite"`), disable the composer for that conversation only. A client abort at about 75 s turns into the `AI_UNAVAILABLE` copy. On success replace the placeholder with the answer and invalidate the conversation list, the messages and `GET /usage`. If no `conversationId` was open, the response's `conversationId` becomes the selected one (`?c=`).
- **Failure handling (the question text must never be lost):**
    - `USAGE_LIMIT_EXCEEDED` (403): the composer is replaced by `LimitPanel` ("You've used all your free questions") with the upgrade dialog (`upgradeContext`) and no further sends.
    - `RATE_LIMITED` (429): inline notice with a countdown from `Retry-After` (reuse `Countdown`/`useCountdown`); the text stays in the composer; send re-enables when it ends.
    - `AI_UNAVAILABLE` (503): inline error on that turn, "The AI service is busy right now. Your question wasn't counted. Try again in a minute.", a **Try again** button, text preserved.
    - `AI_NOT_READY` (409): refetch the index state and switch panels.
    - `CONVERSATION_BUSY`: "Wait for the current answer." `CONVERSATION_FULL`: copy asking to start a new conversation (with a button).
    - `AUTHENTICATION_REQUIRED` goes through the existing single-flight refresh and one retry; never handle it in the feature.
- **Usage meter:** a compact chip near the composer from `useUsage` (the existing `AI_QUERY` entry, `UsageMeter`): "17 of 20 questions left" for FREE, "Unlimited" for PREMIUM, a warning style at 3 left; never a hard-coded 20. If the existing `UsageMeter` can be reused, reuse it.
- A **cached** answer shows a small "from this conversation's cache" note (no usage consumed); the `provider` shows as a quiet "Answered by Gemini/Groq" line (Groq may be shown as "fallback").

### Messages and sources
- Render the answer with a **small in-house safe renderer** (paragraphs, ordered/unordered lists, fenced code blocks, inline code, bold) that produces React elements. **No HTML injection** (`dangerouslySetInnerHTML` is forbidden), no raw HTML from the model, links only if they pass the existing URL validation. Code blocks scroll horizontally and have a copy button. No new Markdown dependency unless the in-house renderer proves inadequate; if it does, report why before adding one.
- Citation markers `[n]` in the answer become small superscript buttons that expand and focus source `n` below the message. A marker with no matching source renders as plain text.
- **Sources** (`SourceChip`): type glyph from `NODE_STYLES`/`Glyph` plus the node name (colour is never the only signal); activating it expands a panel with `file:line–endLine`, the excerpt as a code block (text only), and an **Open in graph** link. The link goes to the Architecture tab with `?node=<encoded id>` when that node is shown there; for a method or function use the owning class (the `parentId` from `GET /nodes/{id}` through an existing hook, or the existing Relationships deep link if the class browser is the right target); if no viewable node exists, show no link. Use `encodeNodeId`. Keyboard operable, `aria-expanded`, visible focus.
- When `sources` is empty show a quiet "No sources cited" line, not an error.
- The system never claims the AI is certain: keep the footer note next to the composer: "Answers are generated by an AI from this repository's code and graph and can be wrong. Your question and code excerpts are sent to Google Gemini or Groq on their free tiers, which may use them to improve their products."

### Re-analyze warning
In the existing re-analyze confirmation (`AnalysisActions`), add: "Ask AI conversations for the current analysis are deleted when the new analysis completes." Keep it in one place so the NOT_INDEXED panel's button shows it too.

## 6. Quality bar
- Accessibility: real buttons and labels, `aria-live` for thinking, errors and indexing progress, focus kept in the composer after send, logical focus when a conversation is deleted, keyboard use of every control, reduced-motion respected.
- Design: Hallmark Coral tokens only (`check:tokens`), no grid or blueprint motifs, existing components (`Button`, `Dialog`, `Notice`, `Skeleton`, `States`, `Locked`, `ToastProvider`). Run the Hallmark sloplint as in `frontend/CLAUDE.md` before shipping.
- Loading, empty, error and success states exist for every query. No spinner can run forever: polling stops at a final state; a failed index query shows the standard error with retry.
- Types are strict; no `any`. Query keys carry every parameter. `staleTime` and invalidation are deliberate (the messages key is per conversation).

## 7. Tests
- **Unit (vitest)**, small inline inputs only: citation parsing and renumbering safety (`[n]` with no source, adjacent markers), the Markdown renderer (code fences, lists, hostile input such as `<script>` and `javascript:` links is rendered as text/dropped), `Retry-After` parsing, source labels, the error-copy mapping for every code.
- **E2E (Playwright, against the running stack, `scripts/e2e/ask-ai.spec.ts`)**: reuse a `COMPLETED` analysis of the test account like `graph-controls.spec.ts`. Cover without spending AI quota: the index panel for the state the account's analysis is in (NOT_INDEXED → re-analyze button and warning; INDEXING → progress; READY → the workspace), creating and deleting a conversation, the URL `?c=` round trip, the empty-state examples filling the composer, and the failure UIs by **injecting network faults** with route interception (403 `USAGE_LIMIT_EXCEEDED`, 429 with `Retry-After`, 503 `AI_UNAVAILABLE`, 409 `AI_NOT_READY`) with the text preserved. A test that asks a **real** question (real answer, real sources, expand a source, Open in graph) runs only with `ARCHYRA_E2E_AI=1` because it spends real provider quota and one `AI_QUERY` unit.
- `npm run verify` stays green (typecheck, lint, `check:tokens`, unit tests, build, session-boot e2e).

## 8. Order of work (commit after each; keep `npm run verify` green)
1. Types, endpoints, query keys, hooks, error copy, `lib/askAi.ts` with unit tests; `node scripts/e2e/probe-api.mjs` confirms the shapes against the live backend.
2. Index panels (states 1 to 4) and the tab wiring (`WorkspaceLayout`, `plans.ts`, landing copy).
3. Conversations and the chat workspace, composer, usage meter, failure handling.
4. Messages, safe renderer, citations and sources.
5. E2E specs, the re-analyze warning, `frontend/CLAUDE.md` and `Architecture.md` (Frontend section) updates, sloplint.

## 9. Acceptance criteria
1. The Ask AI tab no longer says "coming soon" anywhere (tab, landing, plans) and never calls the backend for an analysis that is not `COMPLETED`.
2. Each index state (`NOT_INDEXED`, `INDEXING`, `FAILED` retryable and not, `EMPTY`, `READY`) shows the right panel; polling stops at a final state; Retry and Re-analyze work.
3. A user can create, switch, reload (`?c=`), and delete conversations; history loads in order; limits (`TOO_MANY_CONVERSATIONS`, `CONVERSATION_FULL`) show their copy.
4. Asking a question shows the user message, a thinking state, then the answer with working citation markers and expandable sources (`file:line–endLine`, excerpt, Open in graph where a viewable node exists).
5. The usage meter reads the real `AI_QUERY` entry (FREE remaining, PREMIUM unlimited), updates after each answer, and the limit-reached state replaces the composer with the upgrade dialog.
6. `RATE_LIMITED` shows a live countdown, `AI_UNAVAILABLE` says the question was not counted and offers Try again; the typed text is never lost on any failure.
7. No HTML from the model is ever injected; hostile Markdown renders inert (unit-tested).
8. The free-tier data-use and "can be wrong" note is visible beside the composer; the re-analyze confirmation warns that conversations are deleted.
9. Keyboard-only use works end to end; live regions announce progress and errors; `check:tokens` and the Hallmark lint pass; no `any`, no mocks, components import hooks only.
10. `npm run verify` passes; `frontend/CLAUDE.md` and `Architecture.md` describe the feature.