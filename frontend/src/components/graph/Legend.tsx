import { EDGE_TYPES, NODE_TYPES } from '@/api/types';
import { Glyph } from './Glyph';
import { EDGE_STYLES, NODE_STYLES, markerId } from './graphStyles';

/** Reads from graphStyles, like the canvas: shape and line style carry each meaning as well as colour. */
export function Legend({ nodeTypes = NODE_TYPES.filter((t) => t !== 'REPOSITORY'), edgeTypes = [...EDGE_TYPES] }: { nodeTypes?: readonly string[]; edgeTypes?: readonly string[] }) {
  return (
    <div className="graph-legend" role="group" aria-label="Legend">
      <p className="text-xs font-medium text-ink">Nodes</p>
      <ul className="grid gap-2xs">
        {NODE_TYPES.filter((t) => nodeTypes.includes(t)).map((t) => (
          <li key={t} className="flex items-center gap-xs text-xs text-ink-2">
            <span style={{ color: NODE_STYLES[t].color }}>
              <Glyph glyph={NODE_STYLES[t].glyph} />
            </span>
            {NODE_STYLES[t].label}
          </li>
        ))}
      </ul>
      <p className="mt-xs text-xs font-medium text-ink">Relationships</p>
      <ul className="grid gap-2xs">
        {EDGE_TYPES.filter((t) => edgeTypes.includes(t)).map((t) => {
          const s = EDGE_STYLES[t];
          return (
            <li key={t} className="flex items-center gap-xs text-xs text-ink-2">
              <svg width="40" height="12" viewBox="0 0 40 12" aria-hidden="true" focusable="false" className="shrink-0">
                <line x1="4" y1="6" x2="32" y2="6" style={{ stroke: s.color, strokeWidth: s.width, strokeDasharray: s.dash }} markerEnd={s.markerEnd !== 'none' ? `url(#${markerId(t, 'end')})` : undefined} markerStart={s.markerStart !== 'none' ? `url(#${markerId(t, 'start')})` : undefined} />
              </svg>
              <span className="grid gap-3xs">
                <span>
                  {s.label}
                  <span className="sr-only">: {s.meaning}</span>
                </span>
                {s.note ? <span className="max-w-[16rem] text-muted">{s.note}</span> : null}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
