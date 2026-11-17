# Archyra
> *The architecture behind your code*
>

# Versions

## V0.1 | Codebase Understanding

- Goal: Give Archyra a repository and get a structured representation of it.

---

- [ ]  Accept a GitHub repository
- [ ]  Analyze the repository
- [ ]  Detect files, classes, methods, imports and references
- [ ]  Identify relationships between classes/files
- [ ]  Build the internal code graph
- [ ]  Portray the relationships visually
- [ ]  Show basic technology/framework information

---

- The important thing here is that **Archyra is already doing something genuinely useful without AI**.
- For ex:

    ```tsx
    UserController
          │
          ▼
     UserService
          │
          ▼
     UserRepository
          │
          ▼
       Database
    ```


## V0.5 | Product Foundation

- Goal: Turning the engineering prototype into a service

---

- [ ]  Google authentication
- [ ]  User accounts
- [ ]  Repository history
- [x]  Usage tracking
- [x]  Free usage limits
- [ ]  Admin dashboard
- [ ]  Total users
- [ ]  Active users
- [ ]  Premium users
- [ ]  Analysis statistics
- [ ]  Basic account/settings page

---

- Users should be able to store some data regarding the repositories they have analysed for future references.

## V1 | AI Codebase Explorer

- Goal: Make the AI identity of Archyra visible

---

- [ ]  **Ask AI**
- [ ]  Repository-aware questions, Relationship explanations, Architecture explanations. For example:
    - "How does authentication work?"
    - "Where does a request to `/users` go?"
    - "What classes depend on `PaymentService`?"
    - "Explain this class"
    - "What is the role of this module?"
- [ ]  RAG over the repository/code graph
- [ ]  Refined, modular frontend

---

- The graph tells AI what exists and how things are connected. AI explains what those relationships mean.

    ```tsx
    Repository
         ↓
    Static Analysis
         ↓
    Code Graph
         ↓
     ┌───────────────┐
     │               │
    Graph         Code Context
     │               │
     └───────┬───────┘
             ↓
            AI
             ↓
       Explanation
    ```


---

- [ ]  Deploy Archyra
- [ ]  Put it in resume
- [ ]  Make a linkedin post about it.

## V2 | Interactive Architecture Graph

- Goal: Make the graph explorable.

---

- What it mean?
    - At the first level:

    ```
              UserController
                    │
                    ▼
               UserService
              ↙          ↘
     UserRepository    EmailService
           │                │
           ▼                ▼
       Database          Mail API
    ```

  Then the user clicks:

  **`UserService`**

  Archyra zooms into that class and generates a new graph based on its methods.

  For example:

    ```
                     UserService
                         │
            ┌────────────┼────────────┐
            ▼            ▼            ▼
         getUser()   createUser()  deleteUser()
            │            │            │
            ▼            ▼            ▼
     UserRepository  UserRepository UserRepository
                         │
                         ▼
                      UserMapper
    ```

  And clicking `createUser()` could take you another level deeper:

    ```
    createUser()
         │
         ├── validateUser()
         │
         ├── UserRepository.save()
         │
         ├── PasswordEncoder.encode()
         │
         └── EmailService.send()
    ```

- It should only be available for the premium users.

---

- [ ]  Click node → expand
- [ ]  Double-click → enter node
- [ ]  Hover → relationship information
- [ ]  Search class/method
- [ ]  Filter relationship types
- [ ]  Hide unrelated nodes
- [ ]  Backtrack through exploration
- [ ]  Zoom in/out
- [ ]  Highlight dependency paths
- [ ]  Colour should communicate semantics:
    - class
    - interface
    - external dependency
    - database
    - API
    - method
    - inheritance
    - dependency
    - method call

## V3 | Adaptive Architecture

- Goal: Different graphs for different expertise level. (Beginner, Intermediate, Advanced)
- The free tier should only have the beginner graphs.

---

- Make the difference between free and premium features:


    | Feature | Free | Premium |
    | --- | --- | --- |
    | Repository analysis | ✅ | ✅ |
    | Basic architecture graph | ✅ | ✅ |
    | Actual classes/modules | ✅ | ✅ |
    | Basic relationships | ✅ | ✅ |
    | Ask AI | Limited | More |
    | Interactive graph | ❌ | ✅ |
    | Class → method exploration | ❌ | ✅ |
    | Method-level dependencies | ❌ | ✅ |
    | Deep relationship exploration | ❌ | ✅ |
    | Beginner graph | ✅ | ✅ |
    | Intermediate graph | ❌/limited | ✅ |
    | Expert graph | ❌ | ✅ |
    | Advanced graph filters | ❌ | ✅ |


---

# Technologies

- Backend
    - Java 25 (LTS)
    - Spring Boot
    - Spring Security
    - JWT
    - Maven
- Database
    - PostgreSQL
    - Spring Data JPA
    - Hibernate
- Frontend
    - React
    - Typescript
    - Vite
    - Tailwind
    - React Router
    - TanStack Query
- Graph Visualization
    - React Flow (Backend would give JSON response like: {edges: [], nodes: []})
    - Custom Graph UI from V2
- Code Analysis Engine
    - Tree-sitter, via the official Java binding `io.github.tree-sitter:jtreesitter` (0.27.0)
- Vector Databse
    - PostgreSQL + pgvector
- AI
    - Spring AI
- LLM
    - Use GPT, Gemini and Claude to see which one is giving better response
- Github Integration
    - Github API
    - Git
- Payment
    - Razorpay
- Background Processing (Needed after there are multiple users using the product and server is taking a lot of time to give output)
    - Reddis
    - RabbitMQ

## Notes

### Decision: Tree-sitter binding and Java version (2026-11-14)

- **Chosen:** the official `io.github.tree-sitter:jtreesitter` binding (0.27.0), with the project on **Java 25 (LTS)**.
- **Why Java 25:** jtreesitter uses Java's Foreign Function & Memory API. Versions 0.26.0 and later are compiled for Java 23, and 0.25.6 is the last release that runs on Java 22. Java 22 and 23 are non-LTS releases that no longer get updates, so the project moved to Java 25, the current LTS, which can use the latest binding.
- **Rejected:** the JNI-based `tree-sitter-ng` binding (it would keep Java 21 but isn't the official binding), and Java 22 with jtreesitter 0.25.6 (an outdated binding on an unsupported Java release).
- **Consequences:**
    - jtreesitter does not bundle native code. The tree-sitter runtime library and each language grammar must be available as native shared libraries at runtime, and be packaged for deployment.
    - The JVM needs `--enable-native-access=ALL-UNNAMED` to avoid native-access warnings, and to keep working once those warnings become errors.

### Decision: Code graph semantics for V0.1 (2026-11-15)

- **Node ids** (`nodeId`, scoped by `analysisId`): `repository:<name>`, `module:<package>`, `file:<path>`, `class:<qualified name>`, `interface:<qualified name>`, `method:<qualified name>#<name>(<parameter types>)`, `function:<path>#<name>(<parameter types>)`. External nodes are prefixed `external:`, for example `external:class:java.util.List`. Neo4j internal ids are never exposed.
- **Modules** are Java packages (`(default)` for the default package). Containment is `Repository → Module → File → Class/Interface/Function → Method`, with nested types contained in their enclosing type. Enums and records are `Class` nodes and annotation types are `Interface` nodes; the node property `kind` keeps the original declaration kind.
- **Relationships:**
    - `EXTENDS` and `IMPLEMENTS` between types.
    - `IMPORTS` from a file to the file that declares the imported type (external imports point to an external node).
    - `REFERENCES` from a class (field types) or method (signature, locals, `new`, including generic type arguments) to the types it mentions.
    - `CALLS` between methods, resolved from the declared type of the receiver (field, parameter, local, `this`, `super`, unqualified or static call), including inherited methods.
    - `DEPENDS_ON` between classes and interfaces **of the repository only**, derived from `REFERENCES` and `CALLS`. It is the relationship that draws the architecture (controller → service → repository); external types are never `DEPENDS_ON` targets.
- **External and unresolved targets:** a target outside the repository becomes a node with `external: true` (for example an imported `java.util.List`, or a call on it) instead of being dropped. Heritage that cannot be resolved at all (such as `extends Exception` without an import) also becomes an external node. A reference with no import and no declaration in the repository (a `java.lang` type, a generic type parameter) produces no node. Calls whose receiver type cannot be determined (chained expressions such as `a.b().c()`, accessors generated by Lombok or records) are counted and skipped. Resolution is by declared type, not a full type checker; on `spring-petclinic` roughly a third of calls stay unresolved. Graph endpoints should exclude `external` nodes unless asked for them.
- **Languages:** see "Multi-language analysis" below. An analysis with no parsable file fails with a clear reason.
- **Pipeline and failures:** status and progress live in PostgreSQL (`analyses`); the graph lives in Neo4j. Stages run in the background on a bounded executor, a full queue returns `503 SERVICE_BUSY`, and every error ends as `FAILED` with a user-safe reason.

### Decision: Multi-language analysis (2026-11-15)

- **Goal:** analyse mixed repositories (backend, frontend, mobile, data science, smart contracts, infrastructure), not only Java, without changing the pipeline per language.
- **Analysed languages:** Java, Kotlin, Python, JavaScript/JSX, TypeScript/TSX, Go, Rust, C#, PHP, Ruby, Solidity, Vue and Svelte single-file components (the `<script>` blocks, with original line numbers), Jupyter notebooks (code cells joined as Python). Swift, Dart, C, C++, Scala, R, Julia, shell, SQL, HCL, Dockerfile, HTML and CSS are detected and counted in the language statistics but contribute no code nodes. Adding one is an extractor plus a row in the native build script.
- **Design:** every language is an extractor on a shared `TreeSitterExtractor` base; the IR is language-neutral (`moduleName`, `ImportIR` as symbol/module/wildcard bindings, `Declaration` FULL/PARTIAL/EXTENSION, more `TypeKind`s) and name resolution is generic. Only the mapping from an import specifier to a module of the repository is per language (`ModuleResolver`).
- **Concepts are not forced:** languages without classes (Go, JS scripts, Python scripts) produce `Function` nodes contained in their `File`; Go receiver methods and Rust `impl` blocks merge into their struct; C# `partial` and Ruby reopened classes merge. Top-level script statements are not attributed to a synthetic function.
- **Imports:** `IMPORTS` links a file to the files it imports in any language, following re-exports (Python `__init__`, JS barrels). An unresolved import becomes an external **Module** node (`external:module:numpy`, `external:module:react`) in languages that import modules, and an external Class node in languages that import types (Java, Kotlin, C#, PHP). A call into an external module becomes an external `Function` node.
- **Module dependencies:** `DEPENDS_ON` also connects Module nodes (derived from imports across modules), so function-oriented languages still get an architecture view. Class `DEPENDS_ON` remains class to class within the repository.
- **Resolution limits (static, no type checker):** receivers are typed from declarations, annotations, constructor-parameter properties, `new`/constructor calls and the declared return type of the called function; dynamic languages (Python, JS, Ruby) resolve less than typed ones. Calls on unknown receivers are counted and skipped. Python `self.x` fields are typed from `__init__`; Ruby `@x` from `X.new`.
- **Technologies:** frameworks, libraries and tools are detected from manifests, files, framework languages and imports, using `technologies.tsv` (a data file, one row per library). Languages (with file counts and an `analysed` flag) and technologies (with category, ecosystem and evidence file) are stored in the PostgreSQL `analysis_metadata` table for the repository information endpoint. Detection is best effort and never fails an analysis.

---

# UI

!Archyra UI Journey_ Code to Intelligence.png

---

# System Architecture

## Overall Architecture

```tsx
                                      ┌──────────────────┐
                                      │     Frontend     │
                                      │ React + TS + Vite│
                                      └────────┬─────────┘
                                               │
                                             REST
                                               │
                               ┌───────────────▼───────────────┐
                               │         API Gateway           │
                               │       Spring Boot API         │
                               └───────────────┬───────────────┘
                                               │
              ┌────────────────────────────────┼──────────────────────────┐
              │                                │                          │
              ▼                                ▼                          ▼
     ┌─────────────────┐             ┌─────────────────┐        ┌────────────────┐
     │ Repository      │             │ Analysis        │        │ User / Account │
     │ Service         │             │ Service         │        │ Service        │
     └────────┬────────┘             └────────┬────────┘        └───────┬────────┘
              │                               │                         │
              ▼                               ▼                         ▼
       GitHub / Git                 Code Intelligence Model       PostgreSQL
                                              │
                              ┌───────────────┴───────────────┐
                              ▼                               ▼
                           Neo4j                           pgvector
                         Code Graph                       AI Context
                              │                               │
                              └──────────────┬────────────────┘
                                             ▼
                                        AI Service
                                             │
                                             ▼
                                            LLM
```

## V0.1

### Architecture

```tsx
GitHub Repository
       │
       ▼
Repository Ingestion
       │
       ├── Clone / Download
       ├── File discovery
       ├── Language detection
       └── Project structure
       │
       ▼
Tree-sitter Parsing
       │
       ▼
AST Analysis
       │
       ▼
Symbol / Reference Analysis
       │
       ▼
Archyra Intermediate Representation
       │
       ├── Files
       ├── Classes
       ├── Interfaces
       ├── Methods
       ├── Functions
       ├── Imports
       ├── Calls
       ├── Inheritance
       └── Dependencies
       │
       ▼
Graph Builder
       │
       ▼
		 Neo4j
       │
       ▼
Architecture / Relationship API
       │
       ▼
	Frontend
```

### APIs

## 1. Analyze repository

### `POST /api/v1/analyses`

Starts an analysis.

### RequestBody

```
{
  "repositoryUrl": "https://github.com/user/project"
}
```

### Response

```
{
  "analysisId": "a8f31c",
  "status": "QUEUED"
}
```

I would **not** make this endpoint wait until the entire repository has been analyzed.

Repository analysis can become expensive.

---

## 2. Check analysis status

### `GET /api/v1/analyses/{analysisId}`

### PathVariable

```
analysisId
```

### Response

```
{
  "analysisId": "a8f31c",
  "status": "COMPLETED",
  "progress": 100
}
```

When the status is `FAILED`, the response also carries a `failureReason` (a user-safe message). The field is present only for `FAILED`; it is omitted for every other status.

```
{
  "analysisId": "a8f31c",
  "status": "FAILED",
  "progress": 25,
  "failureReason": "Repository is too large"
}
```

Possible statuses:

```
QUEUED
CLONING
PARSING
ANALYZING
BUILDING_GRAPH
COMPLETED
FAILED
```

---

# 3. Get repository information

### `GET /api/v1/analyses/{analysisId}/repository`

Returns things such as:

```
{
  "name": "payment-service",
  "languages": [
    "Java",
    "SQL"
  ],
  "files": 143,
  "classes": 87,
  "methods": 412
}
```

Semantics (V0.1):

- Only a `COMPLETED` analysis has a repository: unknown or malformed ids return `404 ANALYSIS_NOT_FOUND`; an analysis that is still running or `FAILED` returns `409 ANALYSIS_NOT_COMPLETED`.
- `languages`: every language found, whether or not it has an extractor (SQL above has no code nodes), most files first. `files` is the number of files in those languages, from PostgreSQL (`analysis_metadata`).
- `name`, `classes` and `methods` come from the graph and count the repository's own code only (external nodes excluded). `classes` counts `Class` nodes (enums and records included, interfaces not). `methods` counts `Method` and `Function` nodes, because languages without classes have functions only.

---

# 4. Get architecture graph

### `GET /api/v1/analyses/{analysisId}/graph`

This is the basic V0.1 graph.

### Query parameters

```
?nodeType=CLASS
&relationshipType=DEPENDS_ON
```

Both can be optional.

Example:

```
GET /api/v1/analyses/a8f31c/graph?nodeType=CLASS
```

### Response

```
{
  "nodes": [
    {
      "id": "class-123",
      "type": "CLASS",
      "name": "UserService",
      "file": "src/UserService.java"
    }
  ],
  "edges": [
    {
      "source": "class-123",
      "target": "class-456",
      "type": "DEPENDS_ON"
    }
  ]
}
```

Semantics (V0.1):

- Only a `COMPLETED` analysis has a graph: unknown or malformed ids return `404 ANALYSIS_NOT_FOUND`, an analysis that is running or `FAILED` returns `409 ANALYSIS_NOT_COMPLETED`, and a `nodeType` or `relationshipType` that is not an enum value returns `400 VALIDATION_ERROR` listing the allowed values (case-sensitive, upper case).
- The filters are independent. `nodeType` keeps only nodes of that type. `relationshipType` keeps only edges of that type and does not narrow the nodes. An edge is returned only if both of its ends are returned, so `?nodeType=CLASS` gives the classes and the relationships among them, and no edge points at a missing node. With neither filter the response is the whole graph.
- External nodes (types outside the repository) are never returned, and neither are edges that touch them.
- `file` is left out for nodes that have none (repository, module). Nodes and edges are sorted for a stable response.


### `GET /api/v1/analyses/{analysisId}/nodes/{nodeId}`

Returns information about a class/file/function/etc.

```
{
  "id": "class:com.foo.UserService",
  "type": "CLASS",
  "name": "UserService",
  "qualifiedName": "com.foo.UserService",
  "file": "src/UserService.java",
  "line": 12,
  "kind": "CLASS",
  "parentId": "file:src/UserService.java",
  "relationships": {
    "outgoing": { "CONTAINS": 4, "DEPENDS_ON": 2 },
    "incoming": { "DEPENDS_ON": 1 }
  }
}
```

Semantics (V0.1):

- `nodeId` is the id the graph exposes. Ids contain `/`, `#`, `(`, `)` and spaces, so a client percent-encodes the whole id (`file%3Asrc%2Fmain%2FApp.java`); the server accepts an encoded slash (`TomcatConfig`).
- Any node type works. Fields that do not apply are left out: `kind` is the declaration kind of a type (`CLASS`, `ENUM`, `RECORD`, ...), `language` replaces it for a file (display name, e.g. `Java`), `file` is absent for repository and module nodes, `parentId` is the containing node (absent for the repository).
- `relationships` counts relationships by type to and from other nodes of the repository. External nodes are not counted, and types with no relationship are absent. `/nodes/{nodeId}/relationships` (V2) will list them.
- An external node (outside the repository), an unknown id and an id that is not a node id all return `404 NODE_NOT_FOUND`. Unknown or malformed analysis ids return `404 ANALYSIS_NOT_FOUND`, an analysis that is not `COMPLETED` returns `409 ANALYSIS_NOT_COMPLETED`.

---

# 6. Get repository files

### `GET /api/v1/analyses/{analysisId}/files`

Query parameters:

```
?language=Java
&path=src/main
&page=0
&size=50
```

All optional. Response:

```
{
  "items": [
    { "id": "file:src/main/App.java", "name": "App.java", "path": "src/main/App.java", "language": "Java" }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 143,
  "totalPages": 3
}
```

Semantics (V0.1):

- The filters are independent and combine with AND; with neither, every file is listed. Files are sorted by path.
- `language` is a display name (`Java`, `C#`) or constant (`CSHARP`), in any case; an unknown value returns `400 VALIDATION_ERROR` listing the allowed ones.
- `path` matches the file with exactly that path and everything below it, on whole segments (`src/main` does not match `src/mainframe`), case-sensitive. Leading `./` or `/` and trailing `/` are ignored. At most 500 characters.
- Lists paginate (`page` is zero-based, default size 50, at most 200; anything else is `400`). `totalElements` counts the files that match the filters, over all pages.
- Only files that are part of the graph are listed, i.e. files of analysed languages. Languages that are only counted (SQL, shell, ...) have no files here, although they are in `languages` and `files` of `/repository`.
- Unknown or malformed analysis ids return `404 ANALYSIS_NOT_FOUND`, an analysis that is not `COMPLETED` returns `409 ANALYSIS_NOT_COMPLETED`.

---

# 7. Get a file

### `GET /api/v1/analyses/{analysisId}/files/{fileId}`

Returns metadata and extracted entities.

`fileId` is the file's node id from the `/files` listing (`file:src/main/App.java`), percent-encoded like a `nodeId`.

```
{
  "id": "file:src/main/App.java",
  "name": "App.java",
  "path": "src/main/App.java",
  "language": "Java",
  "module": { "id": "module:com.example", "type": "MODULE", "name": "com.example", "external": false },
  "classes":    [ { "id": "class:com.example.App", "type": "CLASS", "name": "App", "kind": "CLASS", "line": 5, "parentId": "file:src/main/App.java" } ],
  "interfaces": [],
  "methods":    [ { "id": "method:com.example.App#run()", "type": "METHOD", "name": "run", "line": 8, "parentId": "class:com.example.App" } ],
  "functions":  [],
  "imports":    [ { "id": "external:class:java.util.List", "type": "CLASS", "name": "List", "qualifiedName": "java.util.List", "external": true } ],
  "references": [ { "id": "class:com.example.Order", "type": "CLASS", "name": "Order", "qualifiedName": "com.example.Order", "file": "src/main/Order.java", "external": false } ]
}
```

Semantics (V0.1):

- Everything comes from the graph: `classes`, `interfaces`, `methods` and `functions` are the declarations contained in the file (nested types and their methods included, sorted by line); `parentId` says what contains each.
- `imports` are the `IMPORTS` targets: other files of the repository (`type` `FILE`, with `file`) and external types or modules (`external: true`, `qualifiedName` such as `java.util.List`).
- `references` are the `REFERENCES` targets of the file's declarations (field types, signatures, `new`), excluding types declared in the same file. Targets may be external.
- Every list is present and empty when the file has nothing of that kind (a script has no classes). An id that is not a file of the analysis (unknown, a class id, an external node) returns `404 NODE_NOT_FOUND`.

---

# 8. Get a class

### `GET /api/v1/analyses/{analysisId}/classes/{classId}`

Could return:

```
{
  "id": "class-123",
  "name": "UserService",
  "file": "UserService.java",
  "methods": [...],
  "interfaces": [...],
  "dependencies": [...]
}
```

`classId` is the class's node id (`class:com.foo.UserService`), percent-encoded like a `nodeId`. Example response:

```
{
  "id": "class:com.foo.UserService",
  "name": "UserService",
  "qualifiedName": "com.foo.UserService",
  "kind": "CLASS",
  "file": "src/UserService.java",
  "line": 7,
  "parentId": "file:src/UserService.java",
  "superclasses": [],
  "methods":      [ { "id": "method:com.foo.UserService#find(String)", "type": "METHOD", "name": "find", "line": 9 } ],
  "interfaces":   [ { "id": "interface:com.foo.UserApi", "type": "INTERFACE", "name": "UserApi", "qualifiedName": "com.foo.UserApi", "file": "src/UserApi.java", "external": false } ],
  "dependencies": [ { "id": "interface:com.foo.UserRepository", "type": "INTERFACE", "name": "UserRepository", "qualifiedName": "com.foo.UserRepository", "file": "src/UserRepository.java", "external": false } ]
}
```

Semantics (V0.1):

- `/classes` serves `Class` nodes: classes, enums, records and structs, with `kind` telling which. Interfaces are not classes (an interface id returns `404 NODE_NOT_FOUND`); open them with `/nodes/{nodeId}`.
- `methods` are the methods declared directly in the class (not those of nested classes). `interfaces` are the `IMPLEMENTS` targets and `superclasses` the `EXTENDS` targets (a list, because some languages allow several); both may be external (`external: true`, e.g. `java.io.Serializable`). `dependencies` are the `DEPENDS_ON` targets, which are always classes and interfaces of the repository.
- `parentId` is the enclosing class of a nested class, otherwise the declaring file. Every list is present and empty when there is nothing to list.

---

### Database Architecture

### PostgreSQL

For analysis metadata:

```
analyses
repositories
analysis_metadata
```

### Neo4j

For the actual code graph:

```
(:Repository)
(:Module)
(:File)
(:Class)
(:Interface)
(:Method)
(:Function)
```

with relationships such as:

```
IMPORTS
CONTAINS
CALLS
DEPENDS_ON
EXTENDS
IMPLEMENTS
REFERENCES
```

This is the foundation you **do not want to rebuild later**.

## V0.5

### Architecture

```tsx
                         ┌──────────────┐
                         │   Frontend   │
                         └──────┬───────┘
                                │
                         ┌──────▼───────┐
                         │ Spring Boot  │
                         │ API + Auth   │
                         └──────┬───────┘
                                │
            ┌───────────────────┼───────────────────┐
            ▼                   ▼                   ▼
       User Service       Analysis Service      Admin Service
            │                   │                   │
            └───────────────────┼───────────────────┘
                                ▼
                           PostgreSQL
                                │
                                ▼
                              Neo4j
```

### APIs

## Authentication

```
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/logout
```

### `GET /api/v1/auth/me`

Returns the currently authenticated user.

```
{
  "id": "u123",
  "name": "Abhi",
  "email": "..."
}
```

Google OAuth itself can have dedicated auth routes, but I'd let Spring Security handle most of that machinery rather than designing a huge custom authentication API.

#### Implemented in V0.5 (security)

Full spec: `backend/specs/security/spec.md`. How it works, as built:

- **Login methods:** email + password, Google and GitHub (Spring's own `/oauth2/authorization/{google|github}`).
- **Tokens:** a 15-minute access JWT (HS256, `sub` = user id, `roles` claim, never the plan) sent as `Authorization: Bearer ...`, plus an opaque refresh token in an `HttpOnly; SameSite=Strict; Path=/api/v1/auth` cookie, rotated on every use. Presenting an old refresh token revokes the whole login family. Only SHA-256 hashes of refresh, verification and reset tokens are stored.
- **Endpoints under `/api/v1/auth`:** `POST /register`, `/login`, `/refresh`, `/logout`, `/verify-email`, `/resend-verification`, `/forgot-password`, `/reset-password`; `GET /csrf`, `/me`. `/refresh` and `/logout` need the CSRF header (`X-XSRF-TOKEN`, from `GET /csrf`). Register, resend and forgot always answer 202 with the same body, so an email's registration status is never revealed.
- **Social login** ends with a redirect to `{frontend-base-url}/auth/callback` (or `/login?error=<code>`); the SPA then calls `/auth/refresh` like after any login. No token ever appears in a URL.
- **Ownership:** an analysis belongs to the user who started it, and to nobody else (admins included). Anything not yours is a 404, so ids cannot be probed.
- **Premium:** enforced on the server from the database plan (never the token). On `GET .../graph`, FREE gets no parameters, `nodeType`, `relationshipType` and `level=BEGINNER`; everything else is `403 PREMIUM_FEATURE`.
- **Errors** keep the `{ code, message }` shape, e.g. `AUTHENTICATION_REQUIRED` (401), `ACCESS_DENIED`, `CSRF_TOKEN_INVALID`, `PREMIUM_FEATURE` (403), `INVALID_CREDENTIALS` (401), `EMAIL_NOT_VERIFIED`, `ACCOUNT_DISABLED` (403), `RATE_LIMITED` (429, with `Retry-After`).
- **Passwords** are hashed with the delegating encoder (bcrypt), so they are limited to 72 bytes.
- **Deployment notes:** the SPA and the API must share a registrable domain (the refresh cookie is `SameSite=Strict`); behind a proxy use `server.forward-headers-strategy=native`.


---

# User

### `GET /api/v1/users/me`

Get current user.

### `PATCH /api/v1/users/me`

### RequestBody

```
{
  "name": "Abhi"
}
```

---

# Analysis history

This is what I previously called repository management.

### `GET /api/v1/analyses`

Query parameters:

```
?page=0
&size=20
&status=COMPLETED
```

Returns the user's previous analyses.

### `DELETE /api/v1/analyses/{analysisId}`

Deletes an analysis.

### `POST /api/v1/analyses/{analysisId}/reanalyze`

Starts another analysis of the repository.

This is enough. You don't need a massive "repository management" subsystem.

> **Built in V0.5:** `GET /api/v1/analyses` (the caller's own, newest first, `page` and `size`; no `status` filter yet) and `DELETE /api/v1/analyses/{analysisId}` (409 `ANALYSIS_IN_PROGRESS` while it runs). `reanalyze` is not built yet.

---

# Usage

### `GET /api/v1/usage`

Authenticated. The user is always the caller (taken from the security context): there is no `userId` parameter, and nothing in a request can change a counter. Reading never creates rows or takes locks; a user with no row sees `used: 0`. Returns one entry for every usage type, `AI_QUERY` included, so the frontend does not change when the AI chat arrives:

```
{
  "plan": "FREE",
  "usage": [
    { "usageType": "REPOSITORY_ANALYSIS", "used": 3, "limit": 5,  "remaining": 2,  "limitReached": false },
    { "usageType": "AI_QUERY",            "used": 0, "limit": 20, "remaining": 20, "limitReached": false }
  ]
}
```

For a PREMIUM user `limit` and `remaining` are `null` (present, not omitted, not `-1`) and `limitReached` is `false`; `used` is still the true count:

```
{ "usageType": "REPOSITORY_ANALYSIS", "used": 12, "limit": null, "remaining": null, "limitReached": false }
```

`remaining = max(limit - used, 0)` and `limitReached = used >= limit` for FREE. `plan` is the effective plan (an expired PREMIUM shows `FREE`). There is no `resetAt`: the allowance is for life.

> **Built in V0.5** (`usage/` package):
>
> - **Lifetime allowance.** A free user gets `archyra.usage.free-tier.repository-analysis-limit` analyses (default 5) in total, not per month, and nothing resets. The counter is `used_count` in `user_usage`, one row per user and usage type, created on first use. The limits come from configuration only (`archyra.usage.free-tier.*`; a missing or negative value fails startup). `AI_QUERY` exists in the enum and the configuration but nothing consumes it yet.
> - **When a unit is consumed.** `AnalysisService.createAnalysis` calls `AnalysisRegistrar.registerOrReuse`, one short transaction that commits before the background pipeline starts. Every analysis creation goes through it (there is no `reanalyze` yet; when it is built it must use the same registrar).
> - **Locking.** The transaction first creates the user's counter row if needed (`INSERT ... ON CONFLICT DO NOTHING`, so concurrent first requests cannot violate the unique constraint) and then locks it with `SELECT ... FOR UPDATE` (waiting at most 5 s, then `503 SERVICE_BUSY`). That serializes all analysis creation of one user, and only that user, and makes the duplicate check, the limit check, the increment and the insert race-free. Premium users take the same lock so that their counter stays exact. A guarded `UPDATE ... WHERE used_count < :limit` would solve the limit race but not the duplicate-request race, which needs the same serialization anyway.
> - **Duplicates.** Under that lock, a request for a repository URL (compared exactly as validated) that the same user already has in `QUEUED`, `CLONING`, `PARSING`, `ANALYZING` or `BUILDING_GRAPH` returns that analysis (same `202 { analysisId, status }` shape), starts no pipeline and charges nothing. This check runs before the limit check, so a user at 5/5 who resubmits a running URL gets it back instead of a 403. A `COMPLETED` or `FAILED` analysis is not a duplicate: running it again is a new charge.
> - **Limit.** A FREE user with `used_count >= limit` gets `403 USAGE_LIMIT_EXCEEDED` (`{ "code", "message" }`, the message names the configured limit) and no analysis row is created. 403 matches `PREMIUM_FEATURE`: a lifetime limit is not a "retry later" situation, which is what the `429` of `RATE_LIMITED` means.
> - **Premium.** A PREMIUM user (from `User.effectivePlan`, read from the database, never the token) is never blocked, and the counter is still kept. Premium feature gating (`PREMIUM_FEATURE`) is unchanged.
> - **Downgrade (intentional).** `used_count` is everything the user ever consumed. A former premium user with 12 analyses who is back on FREE is already over the limit: `used: 12, limit: 5, remaining: 0, limitReached: true`, and blocked until the limit is raised.
> - **Refunds.** A unit goes back exactly once when the pipeline ends an analysis as `FAILED` (every failure path), when the executor rejects the submission (`503 SERVICE_BUSY`; the row stays as `FAILED`), and, at startup, for analyses interrupted by a restart or failed with a refund that did not go through. `FAILED` and the refund are written in one transaction, so a client that sees `FAILED` already has its unit back. `analyses.quota_charged` and `quota_refunded` make it idempotent: `UPDATE analyses SET quota_refunded = true WHERE id = ? AND quota_charged AND NOT quota_refunded` lets only the first caller go on to the guarded `used_count - 1` (`used_count > 0`, plus a `used_count >= 0` check constraint). A `COMPLETED` analysis is never refunded. Deleting an analysis never refunds either (the allowance is not given back by deleting), and an analysis that no longer exists cannot be refunded. Analyses from before this feature have `quota_charged = false`, so they can never trigger a refund, and users start at `used = 0` (no backfill).

---

# Admin APIs

These should be protected by an `ADMIN` role.

### `GET /api/v1/admin/users`

Query parameters:

```
?page=0
&size=50
```

### `GET /api/v1/admin/stats`

Returns things like:

```
{
  "totalUsers": 1240,
  "activeUsers": 438,
  "premiumUsers": 82,
  "totalAnalyses": 3612
}
```

> **Built in V0.5:** each value is a database `count` query. `activeUsers` is enabled accounts that signed in during the last 30 days (`lastLoginAt`), `premiumUsers` counts PREMIUM plans that have not expired (same rule as `User.effectivePlan`), and `totalAnalyses` counts every analysis whatever its status. ADMIN only.

### `GET /api/v1/admin/users/{userId}`

### `PATCH /api/v1/admin/users/{userId}/status`

RequestBody:

```
{
  "enabled": false
}
```

> **Built in V0.5:** `GET /admin/users`, `GET /admin/users/{userId}` and this `PATCH`. Disabling ends all of the user's sessions, and an admin cannot disable themselves or the last enabled admin (409 `CANNOT_DISABLE_SELF` / `LAST_ADMIN`). `GET /admin/stats` is built (below); `GET /usage` is built (see Usage). Also built: `GET` and `PATCH /api/v1/users/me` (name only).

---

## V1

### Architecture

```tsx
                         User Question
                              │
                              ▼
                         Spring Boot
                              │
                              ▼
                         AI Service
                         /         \
                        /           \
                       ▼             ▼
                  Neo4j Graph     pgvector
                       │             │
                       └──────┬──────┘
                              ▼
                         Context Builder
                              │
                              ▼
                             LLM
                              │
                              ▼
                          AI Answer
```

### APIs

## Ask AI

### `POST /api/v1/analyses/{analysisId}/chat`

### RequestBody

```
{
  "message": "How does authentication work in this repository?"
}
```

### Response

```
{
  "answer": "...",
  "sources": [
    {
      "type": "CLASS",
      "id": "class-123",
      "name": "SecurityConfig"
    }
  ]
}
```

I'd absolutely include sources.

Otherwise the user has no idea whether the AI actually looked at the repository.

---

# Conversation history

### `GET /api/v1/analyses/{analysisId}/conversations`

### `POST /api/v1/analyses/{analysisId}/conversations`

Potentially useful if you want separate conversations.

### `GET /api/v1/conversations/{conversationId}/messages`

---

# Architecture explanation

### `GET /api/v1/analyses/{analysisId}/architecture`

Returns the AI-generated interpretation of the architecture.

For example:

```
{
  "architecture": "Layered Architecture",
  "confidence": 0.91,
  "description": "...",
  "layers": [...]
}
```

The confidence/evidence concept is important because Archyra shouldn't pretend that architecture detection is mathematically certain.

---

# AI file/class explanation

### `POST /api/v1/analyses/{analysisId}/explain`

### RequestBody

```
{
  "nodeId": "class-123",
  "level": "INTERMEDIATE"
}
```

This also gives you a foundation for V3.

### Frontend

```tsx
Dashboard
   │
   └── Repository
          │
          ├── Overview
          ├── Architecture
          ├── Files
          ├── Relationships
          └── Ask AI
```

## V2

### Architecture

```tsx
                         Neo4j
                           │
                           ▼
                    Graph Query Engine
                           │
                    ┌──────┴──────┐
                    ▼             ▼
              Class Graph     Method Graph
                    │             │
                    └──────┬──────┘
                           ▼
                       REST API
                           │
                           ▼
                      React Flow
```

```tsx
Frontend
   │
   │ "Expand UserService"
   ▼
Backend
   │
   ▼
Neo4j traversal
   │
   ▼
Relevant subgraph
   │
   ▼
Frontend
```

### APIs

## Get interactive graph

### `GET /api/v1/analyses/{analysisId}/graph`

This endpoint already exists from V0.1.

**Don't create another endpoint just because V2 arrived.**

Instead, extend it.

For example:

```
GET /api/v1/analyses/{id}/graph
    ?rootNodeId=class-123
    &depth=2
    &relationshipTypes=CALLS,DEPENDS_ON
```

Now the graph can be dynamically generated.

---

# Get relationships of a node

### `GET /api/v1/analyses/{analysisId}/nodes/{nodeId}/relationships`

Query parameters:

```
?direction=OUTGOING
&type=CALLS
&depth=1
```

This is extremely useful for V2.

---

# Expand node

You could even have:

### `GET /api/v1/analyses/{analysisId}/nodes/{nodeId}/expand`

Query parameters:

```
?depth=1
&relationshipTypes=CALLS,DEPENDS_ON
```

Response:

```
{
  "centerNode": {...},
  "nodes": [...],
  "edges": [...]
}
```

---

# Class methods

### `GET /api/v1/analyses/{analysisId}/classes/{classId}/methods`

Returns the methods belonging to a class.

---

# Method dependencies

### `GET /api/v1/analyses/{analysisId}/methods/{methodId}/dependencies`

Query parameters:

```
?direction=BOTH
&depth=2
```

This powers your idea of:

> Click class → see methods → click method → see its dependencies/calls.
>

---

# Graph filters

### `GET /api/v1/analyses/{analysisId}/graph`

Potential query parameters:

```
rootNodeId
depth
nodeTypes
relationshipTypes
direction
maxNodes
```

For example:

```
?rootNodeId=class-123
&depth=2
&nodeTypes=CLASS,METHOD
&relationshipTypes=CALLS,DEPENDS_ON
&direction=BOTH
```

That one endpoint could power a **lot** of V2.

## V3

### Architecture

```tsx
                     Code Intelligence Model
                              │
                              ▼
                       Graph Abstraction
                              │
                 ┌────────────┼────────────┐
                 ▼            ▼            ▼
             Beginner     Intermediate    Expert
                 │            │            │
                 └────────────┼────────────┘
                              ▼
                        Graph Response
```

### APIs

## Get expertise-aware graph

### `GET /api/v1/analyses/{analysisId}/graph`

Again, we extend the existing endpoint.

Query:

```
?level=BEGINNER
```

or:

```
?level=INTERMEDIATE
```

or:

```
?level=EXPERT
```

---

# Beginner

```
GET /api/v1/analyses/123/graph?level=BEGINNER
```

Could return:

```
Frontend
   ↓
API
   ↓
Business Logic
   ↓
Database
```

But it should still be based on the actual repository.

---

# Intermediate

```
GET /api/v1/analyses/123/graph?level=INTERMEDIATE
```

Now show actual architectural components and important relationships.

---

# Expert

```
GET /api/v1/analyses/123/graph?level=EXPERT
```

Potentially expose:

- classes
- interfaces
- methods
- calls
- dependencies
- inheritance
- implementations
- external services
- database interactions

---

# Premium enforcement

This is important.

**Don't trust the frontend to enforce this.**

Bad:

```
Frontend:
if premium:
    show expert graph
```

Anyone can modify that.

Instead:

```
GET /graph?level=EXPERT
             │
             ▼
       Spring Security
             │
             ▼
     Subscription Service
             │
       ┌─────┴─────┐
       │           │
      FREE       PREMIUM
       │           │
       X           ▼
    403 Forbidden   Graph
```

For example:

```
GET /api/v1/analyses/123/graph?level=EXPERT
```

Free user:

```
403 Forbidden
```

with something like:

```
{
  "code": "PREMIUM_FEATURE",
  "message": "Expert graph requires Archyra Premium."
}
```

---

# V3 AI + expertise

You can extend the existing AI endpoint too:

### `POST /api/v1/analyses/{analysisId}/chat`

```
{
  "message": "Explain the authentication flow",
  "expertiseLevel": "BEGINNER"
}
```

Then:

```
BEGINNER
→ conceptual explanation

INTERMEDIATE
→ architecture + important classes

EXPERT
→ detailed implementation + relationships
```

So your graph and AI use the **same expertise concept**.

That's much more elegant than building two separate systems.

---

# 

## Final API Structure

By V3, your API could look roughly like this:

```
/api/v1

├── /auth
│
├── /users
│
├── /analyses
│   ├── POST /
│   ├── GET /
│   ├── GET /{analysisId}
│   ├── DELETE /{analysisId}
│   ├── POST /{analysisId}/reanalyze
│   │
│   ├── GET /{analysisId}/repository
│   ├── GET /{analysisId}/files
│   ├── GET /{analysisId}/files/{fileId}
│   │
│   ├── GET /{analysisId}/classes/{classId}
│   ├── GET /{analysisId}/classes/{classId}/methods
│   │
│   ├── GET /{analysisId}/graph
│   ├── GET /{analysisId}/nodes/{nodeId}
│   ├── GET /{analysisId}/nodes/{nodeId}/relationships
│   ├── GET /{analysisId}/nodes/{nodeId}/expand
│   │
│   ├── GET /{analysisId}/methods/{methodId}/dependencies
│   │
│   ├── GET /{analysisId}/architecture
│   │
│   ├── POST /{analysisId}/chat
│   └── POST /{analysisId}/explain
│
├── /conversations
│
├── /usage
│
└── /admin
    ├── /users
    ├── /users/{userId}
    └── /stats
```
