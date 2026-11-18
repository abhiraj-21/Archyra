/**
 * The Archyra mark: three cards stacked top to bottom and joined by a stem, the way the graph reads a layered
 * codebase (controller, service, repository). Not a blueprint motif: solid shapes, no rules or dimensions.
 */
export function LogoMark({ size = 24, className }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" className={className} aria-hidden="true" focusable="false">
      <rect x="9" y="2.5" width="14" height="7" rx="2" fill="currentColor" />
      <rect x="3" y="12.5" width="14" height="7" rx="2" fill="currentColor" opacity="0.72" />
      <rect x="15" y="22.5" width="14" height="7" rx="2" fill="currentColor" opacity="0.5" />
      <path d="M16 9.5v3M10 19.5v1.5a1.5 1.5 0 0 0 1.5 1.5H22" stroke="currentColor" strokeWidth="1.6" fill="none" strokeLinecap="round" />
    </svg>
  );
}

export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={`inline-flex items-center gap-xs font-display text-lg font-semibold tracking-tight text-ink ${className ?? ''}`}>
      <LogoMark size={22} />
      Archyra
    </span>
  );
}
