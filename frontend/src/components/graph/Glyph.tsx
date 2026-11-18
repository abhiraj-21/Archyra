import type { Glyph as GlyphName } from './graphStyles';

/** The shape that tells node types apart without colour. Drawn in currentColor. */
export function Glyph({ glyph, size = 14 }: { glyph: GlyphName; size?: number }) {
  const common = { width: size, height: size, viewBox: '0 0 14 14', focusable: false as const };
  switch (glyph) {
    case 'square':
      return <svg {...common} aria-hidden="true"><rect x="2" y="2" width="10" height="10" rx="2" fill="currentColor" /></svg>;
    case 'ring':
      return <svg {...common} aria-hidden="true"><circle cx="7" cy="7" r="4.6" fill="none" stroke="currentColor" strokeWidth="2.2" /></svg>;
    case 'dot':
      return <svg {...common} aria-hidden="true"><circle cx="7" cy="7" r="3.4" fill="currentColor" /></svg>;
    case 'diamond':
      return <svg {...common} aria-hidden="true"><path d="M7 1.5 12.5 7 7 12.5 1.5 7Z" fill="currentColor" /></svg>;
    case 'page':
      return <svg {...common} aria-hidden="true"><path d="M3 1.5h5.5L11 4v8.5H3Z M8.5 1.5V4H11" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" /></svg>;
    case 'stack':
      return <svg {...common} aria-hidden="true"><rect x="1.5" y="1.5" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.4" /><rect x="4.5" y="4.5" width="8" height="8" rx="1.5" fill="currentColor" /></svg>;
    case 'hexagon':
      return <svg {...common} aria-hidden="true"><path d="M7 1 12.2 4v6L7 13 1.8 10V4Z" fill="currentColor" /></svg>;
  }
}
