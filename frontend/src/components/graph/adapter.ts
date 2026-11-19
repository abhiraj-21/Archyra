import type { EdgeType, GraphEdge, GraphNode, GraphResponse, NodeType } from '@/api/types';
import { inScope, type Scope } from './scope';

/**
 * The one place API graph data becomes canvas data. Components never reshape API data themselves.
 * External nodes are never shown: the API already leaves them out, and any id with the `external:` prefix that
 * slips through is dropped here too, together with every edge that touches it.
 */
export interface GraphFilter {
  nodeTypes: ReadonlySet<NodeType>;
  edgeTypes: ReadonlySet<EdgeType>;
  /** Applied before the edge check: `tests` is strict, so an edge survives only when both ends are test nodes. */
  scope: Scope;
  /**
   * A name filter, applied on top of the node types and the scope and before the edge check, so an edge shows only when
   * both ends do. Case-insensitive substring of the node's `name` (a module's full name), trimmed; empty means none.
   * On its own it never pulls in neighbours; see `includeDependents`.
   */
  query?: string;
  /**
   * With a query, also show the **dependents** of a match: the nodes that depend on it, one hop, through the selected edge
   * types only, in the "X depends on Y" direction (an edge whose **source** is X and whose **target** is the match Y).
   * Each must still be of a selected node type and in the scope; an edge shows only when both of its ends do. Without a
   * query, or when false or left out, nothing is added. The URL's default (on) is the page's business, not this function's.
   */
  includeDependents?: boolean;
  /**
   * With a query, also show the **dependencies** of a match: the nodes it depends on, one hop, through the selected edge
   * types only (an edge whose **source** is the match and whose **target** is the node). Same rules as `includeDependents`;
   * the two switches are independent.
   */
  includeDependencies?: boolean;
}

export interface VisibleGraph {
  nodes: GraphNode[];
  edges: (GraphEdge & { id: string })[];
  /** Degree within the visible graph, for the node list. */
  degree: Map<string, number>;
  /**
   * The visible nodes that are there only because they depend on a match (never a match themselves). Empty unless
   * `includeDependents` is on and a query is set.
   */
  dependentIds: ReadonlySet<string>;
  /**
   * The visible nodes that are there only because a match depends on them (never a match, and never also in `dependentIds`:
   * a node that is both is shown as a dependent). Every visible node in neither set is a match while a query is set.
   */
  dependencyIds: ReadonlySet<string>;
}

export const isExternalId = (id: string) => id.startsWith('external:');

export function edgeId(edge: GraphEdge): string {
  return `${edge.type}|${edge.source}|${edge.target}`;
}

/** Whether a node's name contains the (already trimmed) query, ignoring case. An empty query matches everything. */
export function matchesQuery(node: Pick<GraphNode, 'name'>, query: string | undefined): boolean {
  const term = query?.trim().toLowerCase();
  return !term || node.name.toLowerCase().includes(term);
}

/**
 * Where the (trimmed, case-insensitive) query sits in a card label, as `[before, hit, after]`, so the card can emphasise the
 * matched part. Null when there is no query or the label does not contain it (a module is matched by its full name but the
 * card shows only its last segment, so the hit may be in the part the card leaves out).
 */
export function splitMatch(label: string, query: string | undefined): [string, string, string] | null {
  const term = query?.trim().toLowerCase();
  if (!term) return null;
  const at = label.toLowerCase().indexOf(term);
  return at < 0 ? null : [label.slice(0, at), label.slice(at, at + term.length), label.slice(at + term.length)];
}

/** What a visible node is while a name filter is on: a match, a dependent of one, or a dependency of one. Null when no filter is on. */
export type NodeRole = 'match' | 'dependent' | 'dependency';

export function nodeRole(id: string, related: { dependentIds: ReadonlySet<string>; dependencyIds: ReadonlySet<string> }, query: string | undefined): NodeRole | null {
  if (!query?.trim()) return null;
  return related.dependentIds.has(id) ? 'dependent' : related.dependencyIds.has(id) ? 'dependency' : 'match';
}

/**
 * How many nodes of the selected types and scope there are, and how many of them the name filter keeps: "Showing 12 of 87".
 * Pure, over the same rules as {@link visibleGraph}.
 */
export function matchCounts(graph: GraphResponse, filter: Pick<GraphFilter, 'nodeTypes' | 'scope' | 'query'>): { shown: number; total: number } {
  let shown = 0;
  let total = 0;
  for (const n of graph.nodes) {
    if (isExternalId(n.id) || !filter.nodeTypes.has(n.type) || !inScope(n, filter.scope)) continue;
    total += 1;
    if (matchesQuery(n, filter.query)) shown += 1;
  }
  return { shown, total };
}

/**
 * A short key for the exact set of visible ids (a 53-bit string hash of the sorted ids), so the layout cache never mixes up
 * two sets that happen to have the same size, which is what a name filter produces.
 */
export function idsKey(nodes: readonly { id: string }[], edges: readonly { id: string }[]): string {
  const hash = (ids: string[]) => {
    let h1 = 0xdeadbeef;
    let h2 = 0x41c6ce57;
    for (const id of ids.slice().sort()) {
      for (let i = 0; i < id.length; i += 1) {
        const c = id.charCodeAt(i);
        h1 = Math.imul(h1 ^ c, 2654435761);
        h2 = Math.imul(h2 ^ c, 1597334677);
      }
      h1 = Math.imul(h1 ^ 0x2c, 2654435761); // a separator, so ["ab"] and ["a", "b"] differ
      h2 = Math.imul(h2 ^ 0x2c, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
  };
  return `${nodes.length}.${hash(nodes.map((n) => n.id))}|${edges.length}.${hash(edges.map((e) => e.id))}`;
}

/**
 * The nodes one hop from a match through the selected edge types, in the "source depends on target" direction. `dependents`
 * are the sources of edges into a match (`security` → `auth` makes `security` a dependent of `auth`); `dependencies` are the
 * targets of edges out of a match (so `auth` is a dependency of `security`). No transitive closure. Only nodes in
 * `candidates` (selected types and scope, not external) qualify, matches are never listed again, each group is found only when
 * `want` asks for it, and a node that is in both groups is a dependent only.
 */
function relatedTo(
  graph: GraphResponse,
  edgeTypes: ReadonlySet<EdgeType>,
  candidates: ReadonlySet<string>,
  matched: ReadonlySet<string>,
  want: { dependents: boolean; dependencies: boolean },
): { dependents: Set<string>; dependencies: Set<string> } {
  const dependents = new Set<string>();
  const dependencies = new Set<string>();
  for (const e of graph.edges) {
    if (!edgeTypes.has(e.type)) continue;
    if (want.dependents && matched.has(e.target) && candidates.has(e.source) && !matched.has(e.source)) dependents.add(e.source);
    if (want.dependencies && matched.has(e.source) && candidates.has(e.target) && !matched.has(e.target)) dependencies.add(e.target);
  }
  for (const id of dependents) dependencies.delete(id);
  return { dependents, dependencies };
}

export function visibleGraph(graph: GraphResponse, filter: GraphFilter): VisibleGraph {
  const candidates = graph.nodes.filter((n) => !isExternalId(n.id) && filter.nodeTypes.has(n.type) && inScope(n, filter.scope));
  const matches = candidates.filter((n) => matchesQuery(n, filter.query));
  let dependentIds: ReadonlySet<string> = new Set();
  let dependencyIds: ReadonlySet<string> = new Set();
  if ((filter.includeDependents || filter.includeDependencies) && filter.query?.trim()) {
    const want = { dependents: !!filter.includeDependents, dependencies: !!filter.includeDependencies };
    const related = relatedTo(graph, filter.edgeTypes, new Set(candidates.map((n) => n.id)), new Set(matches.map((n) => n.id)), want);
    dependentIds = related.dependents;
    dependencyIds = related.dependencies;
  }
  // Same order as the API's nodes, whether a node is a match, a dependent or a dependency.
  const nodes = dependentIds.size + dependencyIds.size === 0 ? matches : candidates.filter((n) => dependentIds.has(n.id) || dependencyIds.has(n.id) || matchesQuery(n, filter.query));
  const ids = new Set(nodes.map((n) => n.id));
  const degree = new Map<string, number>(nodes.map((n) => [n.id, 0]));
  const seen = new Set<string>();
  const edges: VisibleGraph['edges'] = [];
  for (const e of graph.edges) {
    // An edge shows only when both of its ends are visible.
    if (!filter.edgeTypes.has(e.type) || !ids.has(e.source) || !ids.has(e.target)) continue;
    const id = edgeId(e);
    if (seen.has(id)) continue;
    seen.add(id);
    edges.push({ ...e, id });
    degree.set(e.source, (degree.get(e.source) ?? 0) + 1);
    if (e.target !== e.source) degree.set(e.target, (degree.get(e.target) ?? 0) + 1);
  }
  return { nodes, edges, degree, dependentIds, dependencyIds };
}

/** Node ids directly connected to `id` in the visible graph, plus `id`. */
export function neighbourhood(edges: readonly GraphEdge[], id: string): Set<string> {
  const out = new Set([id]);
  for (const e of edges) {
    if (e.source === id) out.add(e.target);
    if (e.target === id) out.add(e.source);
  }
  return out;
}

export function countByType(graph: GraphResponse): Map<NodeType, number> {
  const counts = new Map<NodeType, number>();
  for (const n of graph.nodes) if (!isExternalId(n.id)) counts.set(n.type, (counts.get(n.type) ?? 0) + 1);
  return counts;
}

/** The view a tab opens on when its URL has no `nodes` and no `edges` parameter. */
export interface DefaultView {
  nodeTypes: NodeType[];
  edgeTypes: EdgeType[];
  /** True when this is the modules-joined-by-DEPENDS_ON view (the caption under the filters explains it). */
  moduleDefault: boolean;
}

/**
 * What a URL with an explicit `nodes` or `edges` parameter (and nothing for the other) falls back to: classes and
 * interfaces joined by DEPENDS_ON plus inheritance, reading controller → service → repository; for languages without
 * classes, files and functions. This is the default view of every release before the module default, kept so that
 * links written then behave exactly as they did.
 */
export function classicDefault(graph: GraphResponse): Pick<DefaultView, 'nodeTypes' | 'edgeTypes'> {
  const counts = countByType(graph);
  if ((counts.get('CLASS') ?? 0) + (counts.get('INTERFACE') ?? 0) > 0) {
    return { nodeTypes: ['CLASS', 'INTERFACE'], edgeTypes: ['DEPENDS_ON', 'EXTENDS', 'IMPLEMENTS'] };
  }
  return { nodeTypes: ['FILE', 'FUNCTION'], edgeTypes: ['IMPORTS', 'CALLS', 'CONTAINS'] };
}

/**
 * The initial view: **modules joined by DEPENDS_ON** (no inheritance edges), the shape of the repository before its
 * classes. It is used only when, **after the tab's default scope has been applied** (`working` hides test modules), there
 * are at least two modules and at least one DEPENDS_ON edge between two of them. Otherwise the view is {@link classicDefault},
 * so the first screen is never empty or useless: a repository with a single package still opens on its classes.
 * This is the single source of truth for the Architecture tab and the Overview preview.
 */
export function defaultFilter(graph: GraphResponse, scope: Scope): DefaultView {
  const modules = visibleGraph(graph, { nodeTypes: new Set<NodeType>(['MODULE']), edgeTypes: new Set<EdgeType>(['DEPENDS_ON']), scope });
  if (modules.nodes.length >= 2 && modules.edges.length >= 1) {
    return { nodeTypes: ['MODULE'], edgeTypes: ['DEPENDS_ON'], moduleDefault: true };
  }
  return { ...classicDefault(graph), moduleDefault: false };
}

/** The card label: a module shows its last package segment (the full name stays in the tooltip and inspector). */
export function displayName(node: Pick<GraphNode, 'name' | 'type'>): string {
  if (node.type === 'MODULE' && node.name.includes('.') && !node.name.endsWith('.')) return node.name.slice(node.name.lastIndexOf('.') + 1);
  return node.name;
}

/** Estimated card size, so the layout can run before anything renders. */
export function nodeSize(node: Pick<GraphNode, 'name'> & Partial<Pick<GraphNode, 'type'>>): { width: number; height: number } {
  const label = node.type ? displayName(node as Pick<GraphNode, 'name' | 'type'>) : node.name;
  return { width: Math.round(Math.min(280, Math.max(132, label.length * 7.4 + 64))), height: 44 };
}
