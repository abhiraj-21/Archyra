import { EDGE_TYPES, NODE_TYPES, type EdgeType, type NodeType } from '@/api/types';
import { MAX_SEARCH_LENGTH } from '@/components/graph/graphSearchKeys';

/** Filters live in the URL so a view can be shared: ?nodes=CLASS,INTERFACE&edges=DEPENDS_ON&node=<id>. */
export function parseList<T extends string>(value: string | null, allowed: readonly T[]): T[] | null {
  if (value === null) return null;
  if (value === '') return [];
  const out = value.split(',').filter((v): v is T => (allowed as readonly string[]).includes(v));
  return [...new Set(out)];
}

export const parseNodeTypes = (v: string | null) => parseList<NodeType>(v, NODE_TYPES);
export const parseEdgeTypes = (v: string | null) => parseList<EdgeType>(v, EDGE_TYPES);

/** The longest name filter: one term, no operators. */
export const MAX_QUERY_LENGTH = MAX_SEARCH_LENGTH;

/**
 * `?q=` is the name filter. Trimmed; a missing, blank or over-long value is no filter (an over-long one is not cut, because
 * a half-read term would filter by something nobody typed).
 */
export function parseQuery(value: string | null): string {
  const term = value?.trim() ?? '';
  return term.length > MAX_QUERY_LENGTH ? '' : term;
}

/** What goes in the URL: the trimmed term, or nothing when it is empty or too long, so default URLs stay clean. */
export function queryParam(term: string): string | null {
  const clean = parseQuery(term);
  return clean === '' ? null : clean;
}
