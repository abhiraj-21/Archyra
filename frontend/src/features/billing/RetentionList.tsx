import type { RetentionItem } from '@/api/types';
import { repositoryName, shortDateTime } from '@/lib/billing';

/** What stays and what would go, each with when it was last used. Kept and at risk differ by a heading and a word, not colour. */
export function RetentionList({ kept, atRisk, keep }: { kept: RetentionItem[]; atRisk: RetentionItem[]; keep: number }) {
  return (
    <div className="grid gap-md">
      <div className="grid gap-2xs">
        <h3 className="text-sm font-medium text-ink">Kept ({kept.length} of {keep})</h3>
        <Items items={kept} verdict="Kept" />
      </div>
      {atRisk.length > 0 ? (
        <div className="grid gap-2xs">
          <h3 className="text-sm font-medium text-ink">Would be deleted ({atRisk.length})</h3>
          <Items items={atRisk} verdict="Would be deleted" />
        </div>
      ) : null}
    </div>
  );
}

function Items({ items, verdict }: { items: RetentionItem[]; verdict: string }) {
  if (items.length === 0) return <p className="text-sm text-muted">No repositories.</p>;
  return (
    <ul className="grid gap-3xs text-sm">
      {items.map((item) => (
        <li key={item.analysisId} className="flex flex-wrap items-baseline justify-between gap-x-sm">
          <span className="min-w-0 truncate text-ink">
            <span className="sr-only">{verdict}: </span>
            {repositoryName(item.repositoryUrl)}
            {item.status === 'FAILED' ? <span className="text-muted"> (failed analysis)</span> : null}
          </span>
          <span className="text-xs text-muted">Last used {shortDateTime(item.lastActivityAt)}</span>
        </li>
      ))}
    </ul>
  );
}
