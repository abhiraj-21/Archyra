import { useCallback, useEffect, useRef, useState } from 'react';
import { Search, X } from 'lucide-react';
import { GRAPH_SEARCH_ID, MAX_SEARCH_LENGTH, SEARCH_DEBOUNCE_MS } from './graphSearchKeys';

/**
 * "Filter by name": a case-insensitive substring filter on the node names, one term. The text is local, so typing never
 * waits for anything; the trimmed term is handed to `onCommit` about 250 ms after the last keystroke (and at once for
 * Clear and Escape). A value that changes from outside (Clear filter, Reset filters, a shared URL) replaces the text, but
 * never what is being typed. `nopan nodrag nowheel nokey` keep React Flow's gestures and key handling out of it, so it
 * also works inside the canvas toolbar, in full screen.
 */
export function GraphSearch({ value, onCommit, className = '' }: { value: string; onCommit: (term: string) => void; className?: string }) {
  const [text, setText] = useState(value);
  const committed = useRef(value);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (value !== committed.current) {
      committed.current = value;
      setText(value);
    }
  }, [value]);

  useEffect(() => {
    const term = text.trim();
    if (term === committed.current) return;
    const timer = window.setTimeout(() => {
      committed.current = term;
      onCommit(term);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [text, onCommit]);

  const clear = useCallback(() => {
    setText('');
    committed.current = '';
    onCommit('');
  }, [onCommit]);

  return (
    <div className={`graph-search nopan nodrag nowheel nokey ${className}`}>
      <label htmlFor={GRAPH_SEARCH_ID} className="sr-only">
        Filter by name
      </label>
      <Search size={14} aria-hidden="true" className="graph-search__icon" />
      <input
        ref={input}
        id={GRAPH_SEARCH_ID}
        type="search"
        className="input graph-search__input"
        placeholder="e.g. service"
        maxLength={MAX_SEARCH_LENGTH}
        autoComplete="off"
        spellCheck={false}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Escape' && text !== '') {
            e.preventDefault();
            e.stopPropagation(); // clears the filter; it must not also clear the selected node
            clear();
          }
        }}
      />
      {text !== '' ? (
        <button
          type="button"
          className="btn btn-quiet btn-icon graph-search__clear"
          aria-label="Clear filter"
          onClick={() => {
            clear();
            input.current?.focus();
          }}
        >
          <X size={14} aria-hidden="true" />
        </button>
      ) : null}
    </div>
  );
}
