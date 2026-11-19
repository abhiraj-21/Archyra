/**
 * API contracts, hand-written from the backend DTOs (backend/src/main/java/.../*Response.java, UserProfile,
 * AuthDtos) and checked against live responses with `node scripts/e2e/probe-api.mjs`. Optional fields are the ones
 * the backend leaves out (`@JsonInclude(NON_NULL)`); nullable ones are always present and may be null.
 */

export const ANALYSIS_STATUSES = ['QUEUED', 'CLONING', 'PARSING', 'ANALYZING', 'BUILDING_GRAPH', 'COMPLETED', 'FAILED'] as const;
export type AnalysisStatus = (typeof ANALYSIS_STATUSES)[number];
export const RUNNING_STATUSES: readonly AnalysisStatus[] = ['QUEUED', 'CLONING', 'PARSING', 'ANALYZING', 'BUILDING_GRAPH'];
export const isTerminal = (status: AnalysisStatus): boolean => status === 'COMPLETED' || status === 'FAILED';
export const isRunning = (status: AnalysisStatus): boolean => !isTerminal(status);

export const NODE_TYPES = ['REPOSITORY', 'MODULE', 'FILE', 'CLASS', 'INTERFACE', 'METHOD', 'FUNCTION'] as const;
export type NodeType = (typeof NODE_TYPES)[number];
export const EDGE_TYPES = ['IMPORTS', 'CONTAINS', 'CALLS', 'DEPENDS_ON', 'EXTENDS', 'IMPLEMENTS', 'REFERENCES'] as const;
export type EdgeType = (typeof EDGE_TYPES)[number];
export type UsageType = 'REPOSITORY_ANALYSIS' | 'AI_QUERY';
export type ExpertiseLevel = 'BEGINNER' | 'INTERMEDIATE' | 'EXPERT';

export type Role = 'USER' | 'ADMIN';
export type Plan = 'FREE' | 'PREMIUM';
export type Provider = 'GOOGLE' | 'GITHUB';

export interface ErrorBody {
  code: string;
  message: string;
}

// ---- auth ----
export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  /** Seconds. */
  expiresIn: number;
}
export interface CsrfResponse {
  headerName: string;
  token: string;
}
export interface MessageResponse {
  message: string;
}

/** GET/PATCH /users/me and GET /auth/me. */
export interface UserProfile {
  id: string;
  name: string;
  email: string;
  role: Role;
  plan: Plan;
  emailVerified: boolean;
  linkedProviders: Provider[];
}

/** The two providers as the linked-identities API names them (registration ids), unlike `Provider` elsewhere. */
export type LinkProvider = 'google' | 'github';

/** GET /users/me/linked-identities. `email`/`login`/`linkedAt` are null when the provider gave none or it predates the field. */
export interface LinkedIdentity {
  provider: LinkProvider;
  email: string | null;
  login: string | null;
  linkedAt: string | null;
}

export interface LinkedIdentitiesResponse {
  hasPassword: boolean;
  identities: LinkedIdentity[];
}

/** POST /users/me/linked-identities/{provider}/link-intent: a same-origin path to navigate to (never fetch). */
export interface LinkIntentResponse {
  authorizationUrl: string;
}

// ---- analyses ----
export interface CreateAnalysisResponse {
  analysisId: string;
  status: AnalysisStatus;
}
export interface AnalysisStatusResponse {
  analysisId: string;
  status: AnalysisStatus;
  /** 0 to 100. */
  progress: number;
  /** Present only when status is FAILED. */
  failureReason?: string;
}
export interface AnalysisSummary {
  analysisId: string;
  repositoryUrl: string;
  status: AnalysisStatus;
  progress: number;
  /** ISO-8601 instant. */
  createdAt: string;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ---- repository, graph, nodes ----
export interface RepositoryResponse {
  name: string;
  /** Every language found, most files first, analysed or only counted. Names only: no counts or flags. */
  languages: string[];
  files: number;
  classes: number;
  methods: number;
}
export interface GraphNode {
  id: string;
  type: NodeType;
  name: string;
  /** Absent for repository and module nodes. */
  file?: string;
  /** Code declared in a test file (path and file-name conventions), or a module whose files are all tests. Always present. */
  test: boolean;
}
export interface GraphEdge {
  source: string;
  target: string;
  type: EdgeType;
}
export interface GraphResponse {
  nodes: GraphNode[];
  edges: GraphEdge[];
}
export type RelationshipCounts = Partial<Record<EdgeType, number>>;
export interface NodeResponse {
  id: string;
  type: NodeType;
  name: string;
  qualifiedName?: string;
  file?: string;
  line?: number;
  kind?: string;
  language?: string;
  parentId?: string;
  test: boolean;
  relationships: { outgoing: RelationshipCounts; incoming: RelationshipCounts };
}
export interface EntitySummary {
  id: string;
  type: NodeType;
  name: string;
  kind?: string;
  line?: number;
  parentId?: string;
}
export interface NodeRef {
  id: string;
  type: NodeType;
  name: string;
  qualifiedName?: string;
  file?: string;
  external: boolean;
}
export interface FileSummary {
  id: string;
  name: string;
  path: string;
  language: string;
  test: boolean;
}
export interface FileResponse {
  id: string;
  name: string;
  path: string;
  language: string;
  test: boolean;
  module?: NodeRef;
  classes: EntitySummary[];
  interfaces: EntitySummary[];
  methods: EntitySummary[];
  functions: EntitySummary[];
  imports: NodeRef[];
  references: NodeRef[];
}
export interface ClassResponse {
  id: string;
  name: string;
  qualifiedName?: string;
  kind?: string;
  file?: string;
  line?: number;
  parentId?: string;
  superclasses: NodeRef[];
  methods: EntitySummary[];
  interfaces: NodeRef[];
  dependencies: NodeRef[];
}

/**
 * Every parameter /graph will ever take. V0.1 sends only nodeType and relationshipType; the rest exist so the
 * query key already has a slot for them (spec 6.1).
 */
export interface GraphQuery {
  nodeType?: NodeType;
  relationshipType?: EdgeType;
  level?: ExpertiseLevel;
  rootNodeId?: string;
  depth?: number;
  nodeTypes?: NodeType[];
  relationshipTypes?: EdgeType[];
  direction?: 'INCOMING' | 'OUTGOING' | 'BOTH';
  maxNodes?: number;
}

export interface FilesQuery {
  language?: string;
  path?: string;
  /** true: only test files; false: only the others; absent: both. Applied by the server before paging. */
  test?: boolean;
  page: number;
  size: number;
}

// ---- Ask AI ----
export type AiIndexStatus = 'NOT_INDEXED' | 'INDEXING' | 'READY' | 'FAILED' | 'EMPTY';
export interface AiIndexResponse {
  status: AiIndexStatus;
  chunkCount: number;
  embeddedCount: number;
  embeddingModel: string | null;
  /** True only for FAILED with chunks stored: a free retry is possible. */
  retryable: boolean;
  /** A safe message for FAILED and EMPTY, or a partial-coverage note on a READY index. */
  reason: string | null;
  indexedAt: string | null;
}
/** The id of an Ask AI provider as configured on the server (for example `groq`). It is free-form: the chain is configuration, so never switch on it. */
export type AiProvider = string;
export interface ChatSource {
  /** The number of the [n] marker in the answer that first cited this node. */
  ref: number;
  type: NodeType;
  id: string;
  name: string;
  file: string | null;
  line: number | null;
  endLine: number | null;
  /** The first lines of the chunk the model saw; null for a node known only from a graph fact. */
  excerpt: string | null;
}
export interface ChatAnswer {
  conversationId: string;
  messageId: string;
  answer: string;
  sources: ChatSource[];
  cached: boolean;
  provider: AiProvider;
  /** The provider's display name, for people (the id can be a slug). */
  providerName: string;
}
export interface ChatQuestion {
  message: string;
  conversationId?: string;
}
export interface ConversationSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
}
export interface ChatMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  /** Assistant messages only. */
  sources?: ChatSource[];
  /** Assistant messages only. */
  provider?: AiProvider;
  /** Assistant messages only. */
  providerName?: string;
  cached: boolean;
  createdAt: string;
}

// ---- usage ----
export interface UsageEntry {
  usageType: UsageType;
  used: number;
  /** null for PREMIUM. */
  limit: number | null;
  /** null for PREMIUM. */
  remaining: number | null;
  limitReached: boolean;
}
export interface UsageResponse {
  plan: Plan;
  usage: UsageEntry[];
}

// ---- admin ----
export interface AdminStats {
  totalUsers: number;
  activeUsers: number;
  premiumUsers: number;
  totalAnalyses: number;
}
export interface AdminUser {
  id: string;
  name: string;
  email: string;
  role: Role;
  plan: Plan;
  planExpiresAt: string | null;
  emailVerified: boolean;
  enabled: boolean;
  locked: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  linkedProviders: Provider[];
}
