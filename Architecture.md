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
- [ ]  Usage tracking
- [ ]  Free usage limits
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

---

# 5. Get a specific node

### `GET /api/v1/analyses/{analysisId}/nodes/{nodeId}`

Returns information about a class/file/function/etc.

---

# 6. Get repository files

### `GET /api/v1/analyses/{analysisId}/files`

Query parameters:

```
?language=Java
&path=src/main
```

Both optional.

---

# 7. Get a file

### `GET /api/v1/analyses/{analysisId}/files/{fileId}`

Returns metadata and extracted entities.

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

---

# Usage

### `GET /api/v1/usage`

Returns:

```
{
  "analysesUsed": 3,
  "analysesLimit": 5,
  "resetAt": "..."
}
```

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

### `GET /api/v1/admin/users/{userId}`

### `PATCH /api/v1/admin/users/{userId}/status`

RequestBody:

```
{
  "enabled": false
}
```

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
