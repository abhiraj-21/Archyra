import { useId, useRef } from 'react';
import { CircleHelp } from 'lucide-react';
import { formatNumber } from '@/lib/format';
import { SCOPE_HELP, SCOPE_LABELS, SCOPES, type Scope } from './scope';

/**
 * Working | Tests | All: a radio group (arrow keys move and select, only the checked option is in the tab order).
 * `counts` shows how many nodes each option would reveal under the current filters, so an empty view explains itself.
 */
export function ScopeControl({ value, onChange, counts, className }: { value: Scope; onChange: (scope: Scope) => void; counts?: Record<Scope, number>; className?: string }) {
  const helpId = useId();
  const refs = useRef<Partial<Record<Scope, HTMLButtonElement | null>>>({});

  function onKeyDown(e: React.KeyboardEvent) {
    const i = SCOPES.indexOf(value);
    let next: Scope | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = SCOPES[(i + 1) % SCOPES.length] as Scope;
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = SCOPES[(i + SCOPES.length - 1) % SCOPES.length] as Scope;
    else if (e.key === 'Home') next = SCOPES[0];
    else if (e.key === 'End') next = SCOPES[SCOPES.length - 1] as Scope;
    if (!next) return;
    e.preventDefault();
    e.stopPropagation();
    onChange(next);
    refs.current[next]?.focus();
  }

  return (
    <div className={`flex items-center gap-2xs ${className ?? ''}`}>
      <span aria-hidden="true" className="text-sm text-muted">Show</span>
      <div role="radiogroup" aria-label="Show: working code, tests, or all" aria-describedby={helpId} className="segmented" onKeyDown={onKeyDown}>
        {SCOPES.map((s) => (
          <button
            key={s}
            ref={(el) => {
              refs.current[s] = el;
            }}
            type="button"
            role="radio"
            aria-checked={value === s}
            tabIndex={value === s ? 0 : -1}
            data-scope={s}
            onClick={() => onChange(s)}
          >
            {SCOPE_LABELS[s]}
            {counts ? <span className="num segmented-count">{formatNumber(counts[s])}</span> : null}
          </button>
        ))}
      </div>
      <span id={helpId} className="sr-only">{SCOPE_HELP}</span>
      <span className="scope-help" title={SCOPE_HELP}>
        <CircleHelp size={14} aria-hidden="true" />
        <span className="sr-only">What counts as a test? See the description of the Show control.</span>
      </span>
    </div>
  );
}
