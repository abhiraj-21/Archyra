# Archyra backend

Spring Boot 4 / Java 25 API that analyses a GitHub repository, builds its code graph in Neo4j and serves it to the
frontend. This file covers running it, configuring it and the security set-up. Design notes for contributors are in
`CLAUDE.md`; the security design is in `spec/security/spec.md`, the usage limits in `spec/usage/spec-usage.md` and Ask AI in
`spec/ask-ai-backend/spec.md`.

## Running locally

1. Java 25, Docker (for PostgreSQL, Neo4j and the tests) and a C compiler (for the Tree-sitter libraries).
2. `./scripts/build-native.sh` once. It builds the parser libraries into `native/`.
3. Start PostgreSQL and Neo4j, for example
   `docker run -d -p 7687:7687 -e NEO4J_AUTH=neo4j/<password> neo4j:5.26-community` and a PostgreSQL 17 **with pgvector** and a database called
   `archyra`, for example `docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=<password> -e POSTGRES_DB=archyra pgvector/pgvector:pg17`.
   A plain `postgres` image stops the application at startup with a message saying so (Ask AI stores embeddings in a `vector` column).
   An existing database keeps its data when you switch to the pgvector image of the same major version.
4. `./scripts/fetch-embedding-model.sh` once (about 90 MB). It downloads the local embedding model into `models/` (git-ignored); without it the
   application downloads the same files on first start, and fails with a message that names this script if it cannot.
5. Copy `.env.example` to `.env` and fill in the required values (below). `.env` is git-ignored.
6. `./mvnw spring-boot:run` from `backend/` (it reads `.env`, `native/` and `models/` from the working directory). The API is on port 8080.

For email in development run a local SMTP sink, e.g. `docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog`, set
`SMTP_HOST=localhost SMTP_PORT=1025 SMTP_AUTH=false SMTP_STARTTLS=false`, and read the mails at http://localhost:8025.

## Configuration

Everything is in `src/main/resources/application.properties`; secrets come from environment variables or `.env`.

| Variable | Required | Meaning |
| --- | --- | --- |
| `DB_USERNAME`, `DB_PASSWORD` | yes | PostgreSQL login (the URL is `jdbc:postgresql://localhost:5432/archyra`) |
| `NEO4J_PASSWORD` | yes | Neo4j password. Optional: `NEO4J_URI`, `NEO4J_USERNAME` |
| `JWT_SECRET` | yes | Signs access tokens. Base64 of at least 32 random bytes: `openssl rand -base64 32`. Startup fails if shorter |
| `JWT_ISSUER` | no | Token issuer, default `archyra` |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | yes | Google login |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | yes | GitHub login |
| `FRONTEND_BASE_URL` | no | The SPA, default `http://localhost:5173`. Used for email links and the login redirects, never taken from a request |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated exact origins, default `http://localhost:5173`. `*` is refused |
| `COOKIE_SECURE` | no | `true` by default. Set `false` only for local development over plain HTTP; startup refuses it when the `prod` profile is active |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `MAIL_FROM` | no | Outgoing mail. Defaults point at `localhost:587` |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | no | Creates the first administrator at startup if none exists. Remove `ADMIN_PASSWORD` afterwards |
| `NATIVE_LIB_DIR` | no | Where the Tree-sitter libraries are (default `native`) |
| `ANALYSIS_LIMIT` | yes | Lifetime allowance of repository analyses for FREE users (PREMIUM is never capped). Startup fails if missing or negative |
| `AI_LIMIT` | no | Weekly allowance of Ask AI questions for FREE users, default `15`; it starts again every Monday 00:00 in `USAGE_RESET_ZONE` (PREMIUM is never capped). Startup fails if negative |
| `USAGE_RESET_ZONE` | no | IANA time zone whose Monday midnight starts a new week of Ask AI questions, default `Asia/Kolkata`. An unknown zone fails startup |
| `USAGE_LEDGER_SECRET` | yes | HMAC key of the usage ledger: when an account is deleted only keyed hashes of its email and linked identities are kept, with the counts used, so deleting and registering again does not reset the free allowances. At least 32 bytes of random text (`openssl rand -base64 48`); startup fails if missing or weak. Keep it stable (changing it makes existing entries unmatchable) and never commit it |
| `GEMINI_API_KEY`, `GROQ_API_KEY`, `OPENROUTER_API_KEY`, `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID`, `ZAI_API_KEY`, `COHERE_API_KEY`, `LLM7_API_KEY` | no | Ask AI providers (see *Ask AI* below). Chat needs at least one provider in the chain; indexing needs none. A provider without a key is simply not used, and missing keys never fail startup |
| `OVH_ENABLED` | no | OVHcloud AI Endpoints needs no key (it is used anonymously) and is on by default; `false` leaves it out |
| `NVIDIA_NIM_ENABLED`, `KILO_ENABLED`, `GONKA_ENABLED` (+ `*_API_KEY`) | no | Opt-in providers, off unless `true` (see *Ask AI*) |
| `AI_PROVIDER_ORDER` | no | Comma-separated provider ids, the order they are tried in. Default `gemini,groq,openrouter,cloudflare,zai,cohere,ovh,llm7` |
| `<PROVIDER>_MODEL`, `<PROVIDER>_BASE_URL`, `GROQ_REASONING_EFFORT` | no | Model id and endpoint of a provider (`GEMINI_MODEL`, `OPENROUTER_MODEL`, ...). Defaults, and every local limit, are in `application.properties`. A base URL is what the tests point at a fake server. OpenRouter's model must end in `:free` |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` | no | Razorpay API keys (test or live). Without both, billing is off: the app starts, and billing endpoints other than the plans and the status answer `503 BILLING_UNAVAILABLE` (see *Payments*) |
| `RAZORPAY_WEBHOOK_SECRET` | no | The secret of the Razorpay webhook; without it the webhook refuses every delivery |
| `RAZORPAY_PLAN_MONTHLY`, `RAZORPAY_PLAN_YEARLY` | no | Ids of the Razorpay Plans for auto-renew; without them only one-time payments are offered |
| `BILLING_ZONE` | no | IANA zone of billing periods (calendar months and years) and of the dates in billing emails, default `Asia/Kolkata`. An unknown zone fails startup |
| `EMBEDDING_CACHE_DIR`, `EMBEDDING_MODEL_URI`, `EMBEDDING_TOKENIZER_URI` | no | Where the embedding model is cached (default `models`) and where it comes from |

Fixed behaviour you can tune in `application.properties`: access token 15 min, refresh token 7 days, verification link
24 h, reset link 30 min, 5 failed logins lock an account for 15 min, rate limits (`app.security.rate-limit.*`).

### Provider set-up

Register an OAuth application with each provider and add the redirect URI for the origin the **browser** uses (`{ORIGIN}/login/oauth2/code/{google|github}`). Spring builds it from the request's host, so behind the Vite dev proxy it is the frontend's origin:

| Provider | Where | Redirect URI in local development (through `http://localhost:5173`) |
| --- | --- | --- |
| Google | Google Cloud console → APIs & Services → Credentials → OAuth client of type "Web application" → **Authorized redirect URIs** (any number; "Authorized JavaScript origins" is not needed for this server-side flow) | `http://localhost:5173/login/oauth2/code/google` (keep `http://localhost:8080/login/oauth2/code/google` next to it if you also call the API directly) |
| GitHub | Settings → Developer settings → OAuth Apps → your app → **Authorization callback URL** (a **single** field) | `http://localhost:5173/login/oauth2/code/github` |

- **A GitHub OAuth App holds one callback URL.** It cannot hold both the `:5173` and the `:8080` one. Pointing it at `:5173` stops direct `:8080` sign-in with that app; if both origins are ever needed, register a second OAuth App and keep its own `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` in `backend/.env`.
- Always browse via `http://localhost:5173`, not `127.0.0.1` (a different origin gives a different redirect URI and a mismatch).
- `redirect_uri_mismatch` means the provider's list does not contain the exact value the app sent. Check it with `curl -s -o /dev/null -D - http://localhost:5173/oauth2/authorization/github | grep -i ^location` (the `redirect_uri` query parameter is the value to register). Google changes can take a few minutes to apply, and a consent screen in *Testing* mode only admits the listed test users.
- In production use the public API origin, e.g. `https://app.example.com/login/oauth2/code/google`.

Google needs the `openid profile email` scopes and
GitHub `read:user user:email`; they are already configured. Only a **verified** provider email is trusted.

## Ask AI

Repository-aware chat (V1). After an analysis completes, its code is cut into chunks (captured while the clone still exists), embedded
**locally** (an ONNX MiniLM model inside the JVM, no key, no rate limit) and stored in PostgreSQL with pgvector. A question is answered by
hybrid retrieval (names looked up in the graph, nearest vectors, one hop of graph neighbours) and a chat model, which cites numbered
context items that the server maps back to verified graph nodes. Test code is not indexed. Analyses made before this feature are not
indexed either: re-analyze them.

**Providers.** A chain of free providers, tried in the order of `AI_PROVIDER_ORDER` (default below). A question goes to the first provider that is
configured, not cooling down and within its own limits; on a 429, a 5xx, a timeout, an empty answer, a bad key or an unknown model it goes on to
the next one, up to 4 providers per question. No key at all (and no anonymous provider) means chat answers `503 AI_UNAVAILABLE` without charging.
Gemini has a dedicated client; every other provider speaks the OpenAI chat-completions protocol and is driven by a config block alone.

| Provider (id) | Where to get a key | Default model | Notes |
| --- | --- | --- | --- |
| Gemini (`gemini`) | https://aistudio.google.com/apikey | `gemini-3.6-flash` | Free limits are not published any more. Free-tier prompts may be used by Google; its terms require a paid service when the app is exposed to users in the EEA, Switzerland or the UK |
| Groq (`groq`) | https://console.groq.com/keys | `openai/gpt-oss-120b` | 30 requests/min, 1,000/day, 8,000 tokens/min: hence a small context budget |
| OpenRouter (`openrouter`) | https://openrouter.ai/keys | `google/gemma-4-31b-it:free` | **Only `:free` models: startup refuses anything else.** 20 requests/min, 50/day (1,000 after 10 credits were ever bought: raise `max-requests-per-day`). Free providers may log prompts for training |
| Cloudflare Workers AI (`cloudflare`) | API token from https://dash.cloudflare.com/profile/api-tokens (Workers AI template) and your account id | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | 10,000 neurons/day, reset 00:00 UTC; over it the request fails, nothing is billed. Needs `CLOUDFLARE_ACCOUNT_ID` too |
| Z AI (`zai`) | https://z.ai/manage-apikey/apikey-list | `glm-4.7-flash` | One concurrent request; thinking is switched off. International endpoint `api.z.ai` |
| Cohere (`cohere`) | https://dashboard.cohere.com/api-keys (trial key) | `command-a-plus-05-2026` | **Trial keys are non-commercial use only**; 1,000 calls/month, 20/min. A warning is logged at startup |
| OVHcloud AI Endpoints (`ovh`) | none: used anonymously | `Meta-Llama-3_3-70B-Instruct` | **No `Authorization` header is ever sent** (a key is ignored): keyed use is billed. 2 requests/min per IP and model. On by default; `OVH_ENABLED=false` removes it |
| LLM7 (`llm7`) | https://token.llm7.io (the README of awesome-free-llm-apis) or https://dash.llm7.io (its docs) | `mistral-Nemo-Instruct-2407` | 100,000 tokens/24 h. Catalogue and free quota change without notice; no privacy statement found |

Opt-in providers: **off until `<ID>_ENABLED=true`**, with a startup warning, and appended after the listed ones. NVIDIA NIM (`nvidia-nim`, key from
https://build.nvidia.com): hosted endpoints are for prototyping and log submitted data to improve NVIDIA products. Kilo (`kilo`, no key needed):
its free router may use providers that log prompts. Gonka (`gonka`): the key needs SMS verification with a real, non-VoIP phone number.
**Not implemented:** ModelScope and SiliconFlow (mainland-China real-name verification, cannot be tested from outside) and Aion Labs (a roleplay model).

Model ids and free-tier limits change often (the README of https://github.com/mnfst/awesome-free-llm-apis flags retired models itself): check the
provider's docs and your account's limits page, and change the model (`<PROVIDER>_MODEL`) or the block in `application.properties`, never Java code.
**Adding a provider** that speaks the OpenAI protocol is one block `archyra.ai.providers.<id>.*` (display name, base URL, key, model, context budget
and the local limits) plus its id in `AI_PROVIDER_ORDER`. A local limit of `0` means none. The local limits (requests per minute, calls in flight,
requests per UTC day and month) keep us from spending a free quota on calls that would be refused; the counters live in memory, so a restart resets
them (the provider's own 429 is the backstop). A 429 cools a provider down (its `Retry-After`, else 30 s; repeated 429s double it, up to 1 h); a bad key
or an unknown model cools it down for 30 min with an `ERROR` log. Administrators can see every provider's state at `GET /api/v1/admin/ai/providers`.

**Limits.** FREE users get `AI_LIMIT` questions (default 15) per week, resetting every Monday at 00:00 in `USAGE_RESET_ZONE` (default `Asia/Kolkata`; a failed answer never counts, a cached one is free; repository analyses stay a lifetime allowance), everyone gets 10 questions per
minute (`app.security.rate-limit.ai-chat-per-user-per-minute`). A question that nothing in the repository relates to ("write me a poem") is stopped
before it reaches a provider: `422 QUESTION_NOT_ANSWERABLE`, not counted, not stored (it still takes a token of the per-minute limit); a question that does
reach a provider counts even if the answer is "I can't tell". `archyra.ai.retrieval.min-similarity` decides what "related" means and was calibrated with the
real embedding model (numbers in `Architecture.md`): re-measure it if you change the model. "What is this project about?" is answered from the project's README
and manifests (captured at analysis time) and a few facts about its languages, modules and classes. Tuning keys (`archyra.ai.*`) are in `application.properties`.

**Privacy.** Questions and code excerpts are sent to the third-party providers of the chain. On their free tiers some of them log prompts or use
them to improve their products (see the table). The chat UI says so next to the composer. Archyra only clones public GitHub repositories. Nothing sent or received is ever written to the logs, and API keys never are.

**Embedding model.** `scripts/fetch-embedding-model.sh` downloads `all-MiniLM-L6-v2` (384 dimensions). Its size is fixed by the
`code_chunks.embedding` column: startup fails if a configured model differs. (The `spring-ai-transformers` jar contains only a 133-byte
pointer instead of the model, so the download is always needed.)

### Deployment notes

- **Same site.** The refresh cookie is `SameSite=Strict`, so the SPA and the API must share a registrable domain
  (`localhost:5173` + `localhost:8080`, or `app.example.com` + `api.example.com`). On different sites the cookie
  strategy has to be redesigned; do not just loosen the cookie.
- **HTTPS.** Run production over HTTPS with `COOKIE_SECURE=true` and `FRONTEND_BASE_URL=https://...`.
- **Behind a reverse proxy** keep `server.forward-headers-strategy=native`. Do not switch it to `framework`: in Spring 7 that
  lets any client choose its own IP address and escape the rate limits. Proxies outside the private ranges must be listed
  in `server.tomcat.remoteip.internal-proxies`.
- **Rate limits are per instance** (in memory). Before running several instances move them to a shared store (Redis).
- **Schema.** Tables are created by Hibernate (`spring.jpa.hibernate.ddl-auto=update`). That is fine for development and
  must not be used in production; add migrations (Flyway) first.
- **pgvector.** The database needs the `vector` extension. The application runs `CREATE EXTENSION IF NOT EXISTS vector` at startup, which needs
  a role allowed to do that (the database owner on most managed services), or run it once as a superuser.
- **Embedding model files** must be present on the server (`models/` or the `EMBEDDING_*` settings) and the JVM needs about 300 MB for it.
- **Pre-accounts data.** On startup, analyses created before accounts existed (no owner) are deleted with their graphs.

## Deleting an account and the usage ledger

A signed-in user can delete their account from Settings (`POST /api/v1/users/me/delete` with the password, or `delete-request` then `delete-confirm` with an emailed link for accounts that only use Google or GitHub). Everything the person owns is removed: the account, sessions, linked sign-in methods, every analysis with its graph, Ask AI index and conversations.

**What stays.** So that deleting and registering again does not reset the free allowances, the number of analyses and Ask AI questions already used is kept in a **usage ledger** (`usage_ledger`), indefinitely, for abuse prevention, together with **the link of each repository that still holds a credit** (`usage_ledger_repository`: only the canonical repository URL and when it was analysed; no code, graph, chunks, answers, conversations, email or user id). Both are stored under **keyed hashes** (HMAC-SHA256) of the normalised email and of each linked Google/GitHub identity and cannot be matched to a person without `USAGE_LEDGER_SECRET`. When the same email (a `+tag` variant, or for Gmail also a dotted variant) or the same Google/GitHub identity returns, whether by registering, signing in for the first time or linking the identity to another account, the counts are restored (counts only go up) and the repositories come back as **"Not analysed" entries**, silently: the person can *Analyse* one (it uses the credit already spent and charges nothing more) or *Delete* it (a free user gets that credit back). A returning user is always on the FREE plan: Premium is not carried over. Deleting an account **overwrites** what its keys remember with its current state (so deleting an entry first and the account later leaves the lower count and the matching entries). Ledger rows written before the entries existed restore counts only. This is disclosed in the delete dialog and in the emails of the deletion.

**Upgrading a database.** Hibernate's `ddl-auto=update` creates the new `usage_ledger_repository` table but never relaxes the `CHECK` it created on `analyses.status`; the application widens it at startup (`AnalysisSchemaUpgrade`, idempotent) so the new `NOT_ANALYZED` status can be stored.

Keep `USAGE_LEDGER_SECRET` stable and secret: changing it makes existing ledger entries unmatchable, and losing the database together with the secret would let someone test guessed emails against it. The ledger has no admin UI.

## Payments (Premium through Razorpay)

Spec: `spec/razorpay-premium/spec.md`. A signed-in user buys Premium on `/upgrade`: **700 INR a month or 7000 INR a year** (inclusive of
taxes; `archyra.billing.prices.*`, the client never sends an amount), as a **one-time payment** (a Razorpay Order: one period, renewed by
hand) or **auto-renew** (a Razorpay Subscription on a Plan). Razorpay's Standard Checkout shows the payment methods enabled on the account
(cards, UPI, netbanking, wallets). A paid period moves `users.plan_expires_at` to `max(now, current end)` plus one calendar month or year in
`BILLING_ZONE` (default `Asia/Kolkata`), so renewing early never loses days.

- **Confirmation.** The signed webhook (`POST /api/v1/billing/webhook`) is the source of truth; the checkout's verify call does the same
  idempotent work, and whichever comes first extends Premium, exactly once per payment.
- **The end of Premium.** Three days before the end, a user who will not auto-renew gets a reminder email (and the app shows a banner and a
  dialog after sign-in). At the end they are FREE (as before) and get an "expired" email with the deletion date. **Three days after the end**,
  if they did not renew, only their **5 most recently used repositories** are kept (used = created, completed, re-analysed, asked about in
  Ask AI, opened); the rest, and every failed analysis, are deleted with their graphs and conversations. No credit is returned. Five or fewer
  lose nothing. Refunds are recorded but never shorten Premium by themselves: an administrator uses `PUT /api/v1/admin/users/{id}/plan`.
- **Without keys** the application starts normally, `GET /billing/plans` and `/billing/status` answer, every other billing endpoint is
  `503 BILLING_UNAVAILABLE`, and the payment screen says payments are not available.
- Keys, the webhook secret and plan ids are environment variables (below); nothing is committed and nothing about a payment (keys,
  signatures, bodies, emails, amounts) is logged.

### Razorpay dashboard set-up (done by the owner, not the code)

**Test mode and live mode are separate in Razorpay**: each has its own API keys, webhooks with their own secrets, and Plans with their own
ids. The variable names stay the same; only the values change. Use test values everywhere until live mode is activated.

1. **API keys**: Dashboard → Account & Settings → API Keys → generate a key (test, and later live). Put the key id in `RAZORPAY_KEY_ID` and the
   secret in `RAZORPAY_KEY_SECRET`. The key id reaches the browser (Checkout needs it); the secret never leaves the server.
2. **Auto-capture**: Account & Settings → Payment capture → automatic capture, so a payment is captured (and `payment.captured` sent) at once.
3. **Plans** (Subscriptions → Plans → Create): monthly: period `monthly`, interval 1, amount 70000 paise INR; yearly: period `yearly`,
   interval 1, amount 700000 paise INR. Put their ids in `RAZORPAY_PLAN_MONTHLY` and `RAZORPAY_PLAN_YEARLY`. Without them auto-renew is not
   offered (one-time payments still work). Subscriptions and recurring payment methods must be enabled on the account.
4. **Webhook** (Account & Settings → Webhooks → Add): URL `https://<host>/api/v1/billing/webhook` (public HTTPS; in development use a tunnel
   such as `cloudflared tunnel --url http://localhost:8080` or `ngrok http 8080`), a secret of your choice in `RAZORPAY_WEBHOOK_SECRET`, and these
   events: `payment.captured`, `payment.failed`, `order.paid`, `subscription.authenticated`, `subscription.activated`, `subscription.charged`,
   `subscription.pending`, `subscription.halted`, `subscription.cancelled`, `subscription.completed`, `refund.processed`.
5. **Payment methods**: check which methods are enabled on the account (Account & Settings → Payment methods); Checkout shows exactly those.
6. **Live mode** needs Razorpay's account activation (KYC), then live keys, a live webhook (with its own secret) and live Plans.

### Manual test-mode checklist

With test keys, a tunnel to the webhook and the frontend on `http://localhost:5173`:

- [ ] `/upgrade` shows 700 INR and 7000 INR, the saving, and the comparison; Monthly/Annual and One-time/Auto-renew change the button.
- [ ] **Card success**: pay monthly with Razorpay's test card (`4111 1111 1111 1111`, any future expiry, any CVV, OTP as the test page says).
      The screen says "Premium is active until ..."; Settings shows the exact end; the "Premium is active" email arrives; the dashboard shows
      the `payment.captured` and `order.paid` deliveries as 200 and Premium moved only once.
- [ ] **UPI success**: pay with the test VPA `success@razorpay`. **UPI failure**: `failure@razorpay` shows Razorpay's reason and changes nothing.
- [ ] **Dismiss**: closing the Checkout window says nothing was charged.
- [ ] **Subscription, first charge**: Auto-renew monthly with the test card; Premium starts, Settings says auto-renew is on with the next
      charge date, `subscription.activated` and `subscription.charged` are 200, and Premium moved once. *Cancel auto-renew* keeps Premium until
      the end and Settings says it will not be charged again.
- [ ] **Halt**: in the dashboard, make a test subscription's charge fail until it is halted (or use Razorpay's subscription test tools);
      `subscription.halted` is 200, the subscription shows as not renewing, and the reminder banner appears near the end.
- [ ] A webhook resent from the dashboard (same event id) changes nothing.

## For the frontend

- **Registering an existing address** answers the same `202` as a new one (never an "already registered" error). Show the generic "check your inbox" screen, with links to sign in and to reset the password; the owner is emailed separately.
- **Google or GitHub sign-in** is a full-page navigation to `{API}/oauth2/authorization/google` (or `github`).
  Afterwards the browser lands on `{FRONTEND_BASE_URL}/auth/callback`, or on `/login?error=<code>` with one of
  `oauth_failed`, `oauth_email_unverified`, `oauth_account_disabled`, `oauth_cancelled` (show friendly messages).
- **`/auth/callback` page:** `GET /api/v1/auth/csrf` (use `credentials: 'include'`; it returns `{ headerName, token }` and
  sets the `XSRF-TOKEN` cookie), then `POST /api/v1/auth/refresh` with `credentials: 'include'` and the header
  `X-XSRF-TOKEN: <token>`. The response is `{ accessToken, tokenType, expiresIn }`, exactly like a password login.
- **Keep the access token in memory only** (not `localStorage`) and send `Authorization: Bearer <accessToken>`.
- **On app start and on any 401:** try the refresh once, then send the user to the login page. Do not run two refreshes at the
  same time: the second one uses an already-rotated token and is treated as theft, which ends the session.
- **`403 PREMIUM_FEATURE`:** show an upgrade prompt. The UI may hide premium controls, but the server is the authority.
  FREE users may call `GET .../graph` with no parameters, `nodeType`, `relationshipType` or `level=BEGINNER` only.
- **Errors** are always `{ "code": "...", "message": "..." }`. `429 RATE_LIMITED` carries a `Retry-After` header (seconds).
- **Verification and reset links** are `{FRONTEND_BASE_URL}/verify-email?token=...` and `/reset-password?token=...`; read the
  token, post it to `/api/v1/auth/verify-email` or `/reset-password`, then remove it from the address bar.

## Tests

`./mvnw test` runs everything (Docker must be running: PostgreSQL and Neo4j start through Testcontainers). Use
`./mvnw clean test` after changing signatures, so no stale test classes survive. Opt-in network tests:
`ARCHYRA_NETWORK_TESTS=true ./mvnw test -Dtest='GitClonerTest,RealRepositoryAnalysisTest'`.

The Ask AI tests use the pgvector image (`pgvector/pgvector:pg17`) and the real embedding model; the first run downloads the model (about 90 MB)
into `models/`. The chat tests use WireMock as the fake provider servers (Gemini, Groq, OpenRouter, Z AI, ...). Every Spring context holds a PostgreSQL and a Neo4j
container, so on a small Docker VM (8 GB) the AI test classes close their context when they finish, Spring's test context cache is bounded (`src/test/resources/spring.properties`) and Neo4j's memory is capped; if classes fail with "connection refused" the VM probably ran out of memory (`docker events --filter event=oom`). Opt-in smoke test against the real
providers (one trivial question per provider whose key is set, skipped silently for the others, plus one real grounded question; it spends a
request or two of each free tier): `ARCHYRA_AI_LIVE_TESTS=true ./mvnw test -Dtest=AiLiveSmokeTest`. Providers that need `<ID>_ENABLED=true` (OVH is on by default) are
asked only when switched on.
