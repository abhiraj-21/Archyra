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
   * It never pulls in neighbours.
   */
  query?: string;
}

export interface VisibleGraph {
  nodes: GraphNode[];
  edges: (GraphEdge & { id: string })[];
  /** Degree within the visible graph, for the node list. */
  degree: Map<string, number>;
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

export function visibleGraph(graph: GraphResponse, filter: GraphFilter): VisibleGraph {
  const nodes = graph.nodes.filter(
    (n) => !isExternalId(n.id) && filter.nodeTypes.has(n.type) && inScope(n, filter.scope) && matchesQuery(n, filter.query),
  );
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
  return { nodes, edges, degree };
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
