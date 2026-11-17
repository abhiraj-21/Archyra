# Archyra backend

Spring Boot 4 / Java 25 API that analyses a GitHub repository, builds its code graph in Neo4j and serves it to the
frontend. This file covers running it, configuring it and the security set-up. Design notes for contributors are in
`CLAUDE.md`; the security design is in `specs/security/spec.md`.

## Running locally

1. Java 25, Docker (for PostgreSQL, Neo4j and the tests) and a C compiler (for the Tree-sitter libraries).
2. `./scripts/build-native.sh` once. It builds the parser libraries into `native/`.
3. Start PostgreSQL and Neo4j, for example
   `docker run -d -p 7687:7687 -e NEO4J_AUTH=neo4j/<password> neo4j:5.26-community` and a PostgreSQL 17 with a database called `archyra`.
4. Copy `.env.example` to `.env` and fill in the required values (below). `.env` is git-ignored.
5. `./mvnw spring-boot:run` from `backend/` (it reads `.env` and `native/` from the working directory). The API is on port 8080.

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

Fixed behaviour you can tune in `application.properties`: access token 15 min, refresh token 7 days, verification link
24 h, reset link 30 min, 5 failed logins lock an account for 15 min, rate limits (`app.security.rate-limit.*`).

### Provider set-up

Register an OAuth application with each provider and add this redirect URI (use your real API address):

- Google (Google Cloud console, OAuth client of type "Web application"): `{API_BASE_URL}/login/oauth2/code/google`
- GitHub (Settings, Developer settings, OAuth Apps): `{API_BASE_URL}/login/oauth2/code/github`

For local development `API_BASE_URL` is `http://localhost:8080`. Google needs the `openid profile email` scopes and
GitHub `read:user user:email`; they are already configured. Only a **verified** provider email is trusted.

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
- **Pre-accounts data.** On startup, analyses created before accounts existed (no owner) are deleted with their graphs.

## For the frontend

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
