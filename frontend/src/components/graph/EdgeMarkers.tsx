import { EDGE_TYPES } from '@/api/types';
import { EDGE_STYLES, markerId, type Marker } from './graphStyles';

function shape(marker: Marker, color: string) {
  switch (marker) {
    case 'arrow':
      return <path d="M1 1 11 6 1 11Z" style={{ fill: color }} />;
    case 'open':
      return <path d="M2 2 10 6 2 10" style={{ fill: 'none', stroke: color, strokeWidth: 1.6, strokeLinejoin: 'round', strokeLinecap: 'round' }} />;
    case 'triangle':
      return <path d="M1.5 1.5 10.5 6 1.5 10.5Z" style={{ fill: 'var(--color-canvas)', stroke: color, strokeWidth: 1.4, strokeLinejoin: 'round' }} />;
    case 'diamond':
      return <path d="M1 6 6 1.5 11 6 6 10.5Z" style={{ fill: color }} />;
    case 'none':
      return null;
  }
}

/** One <marker> per edge type and end, defined once for every canvas on the page. */
export function EdgeMarkers() {
  return (
    <svg width="0" height="0" style={{ position: 'absolute' }} aria-hidden="true" focusable="false">
      <defs>
        {EDGE_TYPES.map((type) => {
          const s = EDGE_STYLES[type];
          return (
            <g key={type}>
              {s.markerEnd !== 'none' ? (
                <marker id={markerId(type, 'end')} viewBox="0 0 12 12" refX="10" refY="6" markerWidth="12" markerHeight="12" markerUnits="userSpaceOnUse" orient="auto-start-reverse">
                  {shape(s.markerEnd, s.color)}
                </marker>
              ) : null}
              {s.markerStart !== 'none' ? (
                <marker id={markerId(type, 'start')} viewBox="0 0 12 12" refX="1" refY="6" markerWidth="12" markerHeight="12" markerUnits="userSpaceOnUse" orient="auto">
                  {shape(s.markerStart, s.color)}
                </marker>
              ) : null}
            </g>
          );
        })}
      </defs>
    </svg>
  );
}
