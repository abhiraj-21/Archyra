import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { FileCode2, GitBranch, X } from 'lucide-react';
import { hasCode } from '@/api/errors';
import { EDGE_TYPES, type GraphNode, type RelationshipCounts } from '@/api/types';
import { ComingSoon } from '@/components/ui/Locked';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useNode } from '@/hooks/useWorkspace';
import { formatNumber, humanize } from '@/lib/format';
import { Glyph } from './Glyph';
import { EDGE_STYLES, NODE_STYLES } from './graphStyles';

function Counts({ title, counts }: { title: string; counts: RelationshipCounts }) {
  const rows = EDGE_TYPES.filter((t) => (counts[t] ?? 0) > 0);
  return (
    <div className="grid gap-2xs">
      <h3 className="text-sm text-muted">{title}</h3>
      {rows.length === 0 ? (
        <p className="text-sm text-muted">None</p>
      ) : (
        <dl className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-md gap-y-2xs text-sm">
          {rows.map((t) => (
            <div key={t} className="contents">
              <dt className="text-ink-2">{EDGE_STYLES[t].label}</dt>
              <dd className="num text-right text-ink">{formatNumber(counts[t] ?? 0)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}

/** The docked inspector: what the selected node is, where it lives, and how it connects (from GET /nodes/{id}). */
export function Inspector({ analysisId, nodeId, nodesById, onClose, onSelect, notice }: { analysisId: string; nodeId: string; nodesById: Map<string, GraphNode>; onClose: () => void; onSelect: (id: string) => void; /** A line under the title, e.g. that the node is hidden by the name filter. */ notice?: ReactNode }) {
  const node = useNode(analysisId, nodeId, true);
  const base = nodesById.get(nodeId);
  const style = base ? NODE_STYLES[base.type] : null;

  return (
    <aside className="inspector" aria-label="Node details">
      <div className="flex items-start justify-between gap-sm">
        <div className="flex min-w-0 items-center gap-xs">
          {style ? (
            <span className="arch-node-glyph" style={{ '--node-ink': style.color, '--node-soft': style.soft } as React.CSSProperties}>
              <Glyph glyph={style.glyph} />
            </span>
          ) : null}
          <h2 className="min-w-0 break-words text-lg">{node.data?.name ?? base?.name ?? 'Node'}</h2>
        </div>
        <button type="button" className="btn btn-quiet btn-icon -mr-xs" onClick={onClose} aria-label="Close details">
          <X size={18} aria-hidden="true" />
        </button>
      </div>

      {notice}

      {node.isPending ? (
        <div className="grid gap-sm" role="status" aria-label="Loading details">
          <Skeleton className="h-4 w-1/2" />
          <Skeleton className="h-4 w-3/4" />
          <Skeleton className="h-24 w-full" />
        </div>
      ) : node.isError ? (
        hasCode(node.error, 'NODE_NOT_FOUND') ? (
          <p className="text-sm text-muted">This item is not part of the analysed repository.</p>
        ) : (
          <ErrorState compact error={node.error} onRetry={() => void node.refetch()} />
        )
      ) : (
        <>
          <dl className="grid gap-sm text-sm">
            <div className="grid gap-3xs">
              <dt className="text-muted">Type</dt>
              <dd className="text-ink">
                {NODE_STYLES[node.data.type].label}
                {node.data.kind && node.data.kind !== node.data.type ? <span className="text-muted"> ({humanize(node.data.kind)})</span> : null}
                {node.data.language ? <span className="text-muted"> in {node.data.language}</span> : null}
                {node.data.test ? <span className="chip ml-xs align-middle">Test</span> : null}
              </dd>
            </div>
            {node.data.qualifiedName ? (
              <div className="grid gap-3xs">
                <dt className="text-muted">Qualified name</dt>
                <dd className="num break-all text-xs text-ink">{node.data.qualifiedName}</dd>
              </div>
            ) : null}
            {node.data.file ? (
              <div className="grid gap-3xs">
                <dt className="text-muted">File</dt>
                <dd className="num break-all text-xs text-ink">
                  {node.data.file}
                  {node.data.line ? <span className="text-muted">, line {node.data.line}</span> : null}
                </dd>
              </div>
            ) : null}
            {node.data.parentId ? (
              <div className="grid gap-3xs">
                <dt className="text-muted">Inside</dt>
                <dd>
                  {nodesById.has(node.data.parentId) ? (
                    <button type="button" className="link text-left" onClick={() => onSelect(node.data.parentId as string)}>
                      {nodesById.get(node.data.parentId)?.name}
                    </button>
                  ) : (
                    <span className="num break-all text-xs text-ink-2">{node.data.parentId}</span>
                  )}
                </dd>
              </div>
            ) : null}
          </dl>
          <div className="grid gap-md border-t border-rule pt-md">
            <Counts title="Outgoing" counts={node.data.relationships.outgoing} />
            <Counts title="Incoming" counts={node.data.relationships.incoming} />
          </div>
          <div className="flex flex-wrap gap-xs border-t border-rule pt-md">
            {node.data.file ? (
              <Link className="btn btn-secondary btn-sm" to={`../files?file=${encodeURIComponent(`file:${node.data.file}`)}`}>
                <FileCode2 size={14} aria-hidden="true" /> Open in Files
              </Link>
            ) : null}
            {node.data.type === 'CLASS' || node.data.type === 'INTERFACE' ? (
              <Link className="btn btn-secondary btn-sm" to={`../relationships?item=${encodeURIComponent(node.data.id)}`}>
                <GitBranch size={14} aria-hidden="true" /> Open in Relationships
              </Link>
            ) : null}
            <ComingSoon description="Expanding a node into its methods is planned for a later version.">Expand node</ComingSoon>
          </div>
        </>
      )}
    </aside>
  );
}
