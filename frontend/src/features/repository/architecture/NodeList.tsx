import type { GraphNode } from '@/api/types';
import { Glyph } from '@/components/graph/Glyph';
import { NODE_STYLES } from '@/components/graph/graphStyles';
import { formatNumber } from '@/lib/format';

/** The same visible nodes as a keyboard- and screen-reader-friendly table. */
export function NodeList({
  nodes,
  degree,
  selectedId,
  onSelect,
  dependentIds,
  dependencyIds,
}: {
  nodes: GraphNode[];
  degree: Map<string, number>;
  selectedId: string | null;
  onSelect: (id: string) => void;
  /** Rows that are there only because they depend on a name match, or because a match depends on them, get a text marker. */
  dependentIds?: ReadonlySet<string>;
  dependencyIds?: ReadonlySet<string>;
}) {
  const sorted = [...nodes].sort((a, b) => (degree.get(b.id) ?? 0) - (degree.get(a.id) ?? 0) || a.name.localeCompare(b.name));
  return (
    <div className="h-full overflow-auto px-md py-sm sm:px-lg">
      <table className="table">
        <caption className="sr-only">Visible nodes, most connected first</caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Type</th>
            <th scope="col" className="max-md:hidden">File</th>
            <th scope="col" className="text-right">Connections</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((n) => {
            const s = NODE_STYLES[n.type];
            return (
              <tr key={n.id} aria-selected={n.id === selectedId}>
                <td>
                  <button type="button" className="link-plain text-left font-medium text-ink" onClick={() => onSelect(n.id)} aria-pressed={n.id === selectedId}>
                    {n.name}
                  </button>
                  {dependentIds?.has(n.id) || dependencyIds?.has(n.id) ? (
                    <span className="chip ml-xs" data-tone="quiet">
                      {dependentIds?.has(n.id) ? 'Depends on a match' : 'A match depends on it'}
                    </span>
                  ) : null}
                </td>
                <td>
                  <span className="inline-flex items-center gap-2xs text-ink-2">
                    <span style={{ color: s.color }}>
                      <Glyph glyph={s.glyph} size={12} />
                    </span>
                    {s.label}
                  </span>
                </td>
                <td className="num max-w-[28rem] truncate text-xs text-muted max-md:hidden">{n.file ?? ''}</td>
                <td className="num text-right">{formatNumber(degree.get(n.id) ?? 0)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
