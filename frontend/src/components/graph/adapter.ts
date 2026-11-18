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

export function visibleGraph(graph: GraphResponse, filter: GraphFilter): VisibleGraph {
  const nodes = graph.nodes.filter((n) => !isExternalId(n.id) && filter.nodeTypes.has(n.type) && inScope(n, filter.scope));
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

/**
 * The default view: classes and interfaces joined by DEPENDS_ON (plus inheritance), reading controller → service →
 * repository. Languages without classes fall back to modules joined by DEPENDS_ON, then to files and functions.
 */
export function defaultFilter(graph: GraphResponse): { nodeTypes: NodeType[]; edgeTypes: EdgeType[] } {
  const counts = countByType(graph);
  if ((counts.get('CLASS') ?? 0) + (counts.get('INTERFACE') ?? 0) > 0) {
    return { nodeTypes: ['CLASS', 'INTERFACE'], edgeTypes: ['DEPENDS_ON', 'EXTENDS', 'IMPLEMENTS'] };
  }
  const moduleEdges = graph.edges.some((e) => e.type === 'DEPENDS_ON' && e.source.startsWith('module:'));
  if ((counts.get('MODULE') ?? 0) > 1 && moduleEdges) return { nodeTypes: ['MODULE'], edgeTypes: ['DEPENDS_ON'] };
  return { nodeTypes: ['FILE', 'FUNCTION'], edgeTypes: ['IMPORTS', 'CALLS', 'CONTAINS'] };
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
