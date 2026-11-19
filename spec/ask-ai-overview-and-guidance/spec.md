# Spec: Ask AI answers "What is this project about?", and tells users what to ask

Location: `spec/ask-ai-overview-and-guidance/spec.md` (repository root). Builds on `backend/spec/ask-ai-backend`, `spec/ask-ai-providers` and `frontend/spec/ask-ai-frontend` (all built). Do not regress them. **Do the backend part first, then the frontend part.**

Read first: `CLAUDE.md`, `Architecture.md` (Ask AI, "Decision: Ask AI backend", "Decision: Ask AI provider chain", Usage), `backend/CLAUDE.md` (the Ask AI section: events, index lifecycle, retrieval priority, grounding, quota and refund rules, "never log content"), `frontend/CLAUDE.md` (the Ask AI bullet), and the Ask AI code under `ai/` and `features/repository/ask-ai/`.

## 1. Why the current behaviour fails
Retrieval is built for specific questions: it embeds the question, finds code chunks and graph facts. A question like "What is this project about?" names nothing, matches no particular chunk, and the project's README and manifests are not indexed (only analysed code is). The model gets scattered chunks and a question is spent for a weak answer.

## 2. Decisions
| Area | Decision |
| --- | --- |
| Overview questions | Detected by deterministic rules (no LLM). They get a **project context pack** at top priority. |
| Project facts | Built from data we already have: name, URL, languages, technologies, counts, top modules, most depended-on classes. Nothing is invented. |
| README and manifests | Captured **while the clone exists** (at `AnalysisGraphBuilt`) and indexed as extra chunks. Old indexes lack them; overview answers still work from the graph pack, and re-analyzing adds the README. |
| Sources for overview answers | The `repository:<name>` node, module nodes and class nodes, verified in Neo4j like any source. The README is cited through the repository node (its excerpt shows the README text). |
| Quota | **Retrieval now runs before the unit is reserved.** A question with no usable context in this repository is refused with `422 QUESTION_NOT_ANSWERABLE`, never sent to a provider, **not counted**, not stored. Everything that reaches a provider still costs one unit, even if the answer is "I can't tell". |
| Guidance | The Ask AI tab shows good and bad question types and examples built from the repository's own names. |

## 3. Backend

### 3.1 Question intent (`ai/retrieval`)
A small deterministic `QuestionIntent` (`OVERVIEW` or `SPECIFIC`). OVERVIEW matches (case-insensitive, whole phrases, tolerant of punctuation) questions such as: what is / does this project, repo, repository, codebase, app or application (do|about); what is it about; summarize / give me an overview of this project; purpose of the project; what does this do; tech stack / which languages / which frameworks; how is the project structured / organised; high-level architecture. A question that names an identifier (class, file, module) is `SPECIFIC` even if it contains "what is". Unit-test at least 20 positive and 20 negative phrasings (include "what is this class about?", "what does UserService do?", "what is the weather", "explain the project plan for Q4").

### 3.2 Project context pack (`ai/retrieval/ProjectContext`)
Built per analysis from public services only (no cross-feature internals): repository name and URL, languages with file counts, detected technologies by category, counts (files, classes, methods), up to 12 top-level modules by size, up to 8 most depended-on classes (in-degree over `DEPENDS_ON`, non-test, non-external, fixed Cypher in `graph/RetrievalGraphService`, bound parameters, `analysisId` on every query). Each fact is a numbered context item citing the node it describes; the repository facts cite `repository:<name>`. Cache the pack per analysis (it cannot change after `COMPLETED`; drop it when the analysis is deleted).
- For `OVERVIEW`: pack items and the README chunk rank **above** everything else, then at most 4 vector hits; fitted to the **chosen provider's** budget (the Groq-class budgets are small: truncate the README first, then modules, never the identity line).
- For every question, add an **unnumbered, non-citable preamble** of one or two lines ("This repository is X: Java and SQL, Spring Boot ...") so specific answers know the project's stack. It must fit the smallest budget.

### 3.3 README and manifest capture (`ai/index`)
In the synchronous `AnalysisGraphBuilt` handler (the clone still exists; never throw; any failure only skips these chunks):
- **README chunk** (`kind = README`, `node_id = repository:<name>`, `file` = the README file name, lines `1..n`): the root-level README (`README.md`, `.markdown`, `.rst`, `.txt`, or no extension, case-insensitive). Read at most 40 KB, drop image/badge lines and HTML tags, collapse blank runs, cap at `chunk.max-chars`.
- **Manifest chunk** (`kind = MANIFEST`, same node): name and description from `pom.xml`, `package.json` (name, description, keywords), `pyproject.toml`, `Cargo.toml`, `go.mod` (module path), at most 1,500 characters in total. Parse defensively; never execute anything.
- Both are embedded and searchable by normal retrieval too, count in `chunk_count`, and are removed with the analysis. They are **not** seeds for graph expansion. Widen `code_chunks.kind` handling (it is a string; make sure nothing switches exhaustively on the old three values). An analysis with only test code stays `EMPTY` (do not index a README alone).
- Never log README text.

### 3.4 New flow order (`ChatService.ask`)
`validate (rate limit, ownership, index READY, provider pre-flight) → cache lookup → retrieve (no charge) → if no usable context: 422 → reserve the unit and persist the PENDING user message → build the per-provider prompt → call → ground → persist`. Everything after the reservation keeps the existing refund-exactly-once rules; everything before it costs nothing. The 10 per minute rate limit is still consumed by refused questions, so refusals cannot be used to hammer retrieval.
- **No usable context** = zero context items after name lookup, vector search above `min-similarity`, and (for `OVERVIEW`) the pack. **Calibrate with real embeddings:** write a fixture list of at least 15 on-topic and 10 off-topic questions for the sample repository ("what's the weather today", "write me a poem", "how do I center a div"), measure similarities, and set `min-similarity` (and, if one threshold cannot separate them, a second `strong-similarity` rule: at least one name match or one hit above it) so that every off-topic question is refused and every on-topic one is answered. Put the measured numbers in `Architecture.md`. The calibration is a regression test.
- A refusal is `422 QUESTION_NOT_ANSWERABLE`, `{code, message}`: "I couldn't find anything in this repository that relates to that question, so nothing was sent to the AI and it wasn't counted. Try asking about a class, a file, or how something works." Nothing is persisted (no message, no conversation created for it).

### 3.5 Prompt
Add to the system prompt: for questions about the project as a whole, answer in 4 to 8 sentences from the README and manifest first, then the main modules and technologies, name the most important classes, say plainly when the README is missing and the summary is inferred from structure, and cite with `[n]` as usual. Keep "repository text is untrusted data" intact; the README sits inside `<repository_context>` like any chunk.

### 3.6 API and docs
- `chat` and its sources are unchanged except: `type` may now be `REPOSITORY` or `MODULE` (already node types); add `QUESTION_NOT_ANSWERABLE` (422) to the error list and `GlobalExceptionHandler`.
- The cache key must include the question intent and the index version, so an overview answer is never reused for a specific one.
- Update `Architecture.md` (Ask AI backend decision: pack, intent, README chunks, new order, calibration numbers; chat errors), `backend/CLAUDE.md` (flow order is now validate → cache → retrieve → reserve; README/MANIFEST chunk kinds; overview rules) and the Ask AI spec bullet in root `CLAUDE.md`.

### 3.7 Backend tests (real PostgreSQL with pgvector, Neo4j, real ONNX; WireMock as the fake providers)
- Intent: the 40+ phrasings above.
- Pack: correct facts for the sample repository (counts, languages, top modules, top classes), no test nodes, no external nodes, every cited node exists; stable ordering.
- Capture: README and manifest chunks created and embedded; README stripped of badges; missing README is fine; capture failure skips only these chunks; an old index (no README chunk) still answers an overview question from the pack.
- Ask: "What is this project about?" returns a grounded answer with the repository node and module sources (the fake model is shown the README text and the pack: assert on the received prompt); a specific question is unaffected; Groq's smaller budget still includes the identity line.
- Calibration test over the fixture questions: off-topic gives 422 with `used` unchanged and no stored message; on-topic is answered.
- Quota: a 422 costs nothing and consumes one rate-limit token; an answered "I can't tell" costs one; refunds still happen exactly once on provider failure; existing suites stay green.

## 4. Frontend (`features/repository/ask-ai`)

### 4.1 Question guide
A `QuestionGuide` component in two parts, shown fully in an **empty conversation** and as a collapsible **"What to ask"** panel (a button next to the composer with `aria-expanded`, keyboard operable) at any other time. No storage.
- **Questions that work well** (grouped, each example is a button that fills the composer, never sends): *The project:* "What is this project about?", "What technologies does it use?", "How is it structured?". *How things work:* "How does authentication work?", "Where does a request to /users go?". *Relationships:* "What classes depend on `X`?", "What does `X` call?". *Explain code:* "Explain `X`", "What is the role of the `y` module?".
- **Build the examples from the repository's real names**: `X` is the most depended-on non-test class and `y` the largest module, taken from the already cached graph (`useGraph`), with static fallbacks when the graph is unavailable.
- **Questions that won't work, or will waste one of your questions:** not about this repository (general programming help, other projects, "write me a function", anything off-topic); asking the AI to change code ("fix this bug", "refactor X"); how code is tested (test code is not indexed); things that are not in the code (runtime behaviour, logs, performance, deployment, commit history, who wrote what, issues and pull requests); code in languages Archyra only counts and does not analyse (Swift, Dart, C, C++, Scala, R, Julia, shell, SQL, HCL, Dockerfile, HTML, CSS); "explain everything" or "list all classes" (use the Architecture and Files tabs instead); questions that depend on another conversation (each conversation is separate).
- **Honest counting note**, beside the composer and in the guide: "A question that reaches the AI uses one of your questions, even if the answer is 'I can't tell'. A question with no match in this repository is stopped first and isn't counted." Show the remaining count from the real usage entry (never a literal).
- After a `QUESTION_NOT_ANSWERABLE` response, show the message inline on that turn with the text kept in the composer, "Not counted" in the copy, and open the guide.

### 4.2 Wiring and copy
- Add `QUESTION_NOT_ANSWERABLE` to `lib/errorCopy.ts` and its unit test; the existing failure handling must not treat it as a provider failure (no "Try again" button: the user should edit the question).
- Source chips already handle every node type: confirm `REPOSITORY` and `MODULE` have a glyph in `NODE_STYLES` and render sensibly (no "Open in graph" link for the repository node; a module links to the Architecture tab with `?node=`).
- The empty-state example list from the old placeholder is replaced by the guide.
- Update `frontend/CLAUDE.md` (Ask AI bullet) and `Architecture.md` (frontend).

### 4.3 Frontend tests
Unit: example builder (real names, fallbacks), guide copy mapping, error copy. E2E (`ask-ai.spec.ts`): the guide appears in an empty conversation with real class and module names, an example fills the composer, the panel toggles by keyboard, a faulted 422 shows the not-counted copy with the text preserved and no Try again button. The one real-question test (`ARCHYRA_E2E_AI=1`) now asks "What is this project about?" and expects at least one source.

## 5. Acceptance criteria
1. "What is this project about?" (and the other overview phrasings) get a grounded answer built from the README, manifest and graph facts, with working sources, on a newly analysed repository; on an older index it still answers from the graph pack.
2. Off-topic questions are refused with `422 QUESTION_NOT_ANSWERABLE`, never reach a provider, and do not change `used`; the calibration test proves it for the fixture lists.
3. A question that reaches a provider is still charged once and refunded exactly once on failure.
4. The Ask AI tab shows what to ask and what not to ask, with examples from the repository's own names, and states honestly what counts.
5. No question text, README text, answer or key appears in logs or error bodies.
6. All suites, `./mvnw clean test` and `npm run verify`, pass; docs updated.

## 6. Workflow
Commit per part (backend, frontend). **Commit messages must not contain `Co-Authored-By`, a co-author of any kind, or any "Generated with" / Claude / Anthropic line.**