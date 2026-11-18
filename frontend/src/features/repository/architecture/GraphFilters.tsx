import { SlidersHorizontal } from 'lucide-react';
import type { EdgeType, NodeType } from '@/api/types';
import { Glyph } from '@/components/graph/Glyph';
import { EDGE_STYLES, FILTER_EDGE_TYPES, FILTER_NODE_TYPES, NODE_STYLES } from '@/components/graph/graphStyles';
import { ComingSoon } from '@/components/ui/Locked';
import { formatNumber } from '@/lib/format';

interface Props {
  nodeTypes: NodeType[];
  edgeTypes: EdgeType[];
  counts: Map<NodeType, number>;
  onNodeTypes: (types: NodeType[]) => void;
  onEdgeTypes: (types: EdgeType[]) => void;
}

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

/** Live filters (applied client-side to the one fetched graph) plus the not-yet-built controls, marked as such. */
export function GraphFilters({ nodeTypes, edgeTypes, counts, onNodeTypes, onEdgeTypes }: Props) {
  return (
    <details className="graph-filters group" open>
      <summary className="flex cursor-pointer list-none items-center gap-xs py-xs text-sm font-medium text-ink-2 lg:hidden">
        <SlidersHorizontal size={16} aria-hidden="true" /> Filters and view
      </summary>
      <div className="grid gap-sm pb-sm lg:flex lg:flex-wrap lg:items-center lg:gap-x-lg">
        <fieldset className="flex flex-wrap items-center gap-x-sm gap-y-2xs">
          <legend className="sr-only">Node types</legend>
          <span aria-hidden="true" className="text-sm text-muted">Show</span>
          {FILTER_NODE_TYPES.map((t) => {
            const s = NODE_STYLES[t];
            const n = counts.get(t) ?? 0;
            return (
              <label key={t} className="filter-option">
                <input type="checkbox" checked={nodeTypes.includes(t)} disabled={n === 0} onChange={() => onNodeTypes(toggle(nodeTypes, t))} />
                <span style={{ color: s.color }}>
                  <Glyph glyph={s.glyph} size={12} />
                </span>
                {s.plural}
                <span className="num text-xs text-muted">{formatNumber(n)}</span>
              </label>
            );
          })}
        </fieldset>
        <fieldset className="flex flex-wrap items-center gap-x-sm gap-y-2xs">
          <legend className="sr-only">Relationship types</legend>
          <span aria-hidden="true" className="text-sm text-muted">Join by</span>
          {FILTER_EDGE_TYPES.map((t) => {
            const s = EDGE_STYLES[t];
            return (
              <label key={t} className="filter-option">
                <input type="checkbox" checked={edgeTypes.includes(t)} onChange={() => onEdgeTypes(toggle(edgeTypes, t))} />
                <svg width="22" height="8" viewBox="0 0 22 8" aria-hidden="true" focusable="false">
                  <line x1="1" y1="4" x2="21" y2="4" style={{ stroke: s.color, strokeWidth: s.width + 0.5, strokeDasharray: s.dash }} />
                </svg>
                {s.label}
              </label>
            );
          })}
        </fieldset>
      </div>
    </details>
  );
}

/** Controls the backend does not have yet (levels, search, depth), marked coming soon for every plan. */
export function GraphUpcoming({ onReset }: { onReset: () => void }) {
  return (
    <div className="flex flex-wrap items-center gap-x-xs gap-y-2xs">
      <span className="text-sm text-muted">Level</span>
      <span className="chip" data-tone="ink" aria-current="true">Beginner</span>
      <ComingSoon description="Intermediate graphs are not built yet.">Intermediate</ComingSoon>
      <ComingSoon description="Expert graphs are not built yet.">Expert</ComingSoon>
      <ComingSoon description="Searching the graph is planned for a later version.">Search</ComingSoon>
      <ComingSoon description="Depth and advanced filters are planned for a later version.">Depth</ComingSoon>
      <button type="button" className="btn btn-quiet btn-sm" onClick={onReset}>
        Reset filters
      </button>
    </div>
  );
}
