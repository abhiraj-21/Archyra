import { EDGE_TYPES, NODE_TYPES, type EdgeType, type NodeType } from '@/api/types';

/** Filters live in the URL so a view can be shared: ?nodes=CLASS,INTERFACE&edges=DEPENDS_ON&node=<id>. */
export function parseList<T extends string>(value: string | null, allowed: readonly T[]): T[] | null {
  if (value === null) return null;
  if (value === '') return [];
  const out = value.split(',').filter((v): v is T => (allowed as readonly string[]).includes(v));
  return [...new Set(out)];
}

export const parseNodeTypes = (v: string | null) => parseList<NodeType>(v, NODE_TYPES);
export const parseEdgeTypes = (v: string | null) => parseList<EdgeType>(v, EDGE_TYPES);
