import { Link } from 'react-router';
import { X } from 'lucide-react';
import { hasCode } from '@/api/errors';
import type { EntitySummary, NodeRef } from '@/api/types';
import { Glyph } from '@/components/graph/Glyph';
import { NODE_STYLES } from '@/components/graph/graphStyles';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useFile } from '@/hooks/useWorkspace';
import { humanize } from '@/lib/format';

function Section({ title, count, children }: { title: string; count: number; children: React.ReactNode }) {
  return (
    <section className="grid gap-2xs border-t border-rule pt-sm">
      <h3 className="flex items-baseline justify-between text-sm text-muted">
        {title} <span className="num text-xs">{count}</span>
      </h3>
      {count === 0 ? <p className="text-sm text-muted">None</p> : <ul className="grid gap-3xs">{children}</ul>}
    </section>
  );
}

function Entity({ e }: { e: EntitySummary }) {
  const s = NODE_STYLES[e.type];
  return (
    <li className="flex items-baseline gap-xs text-sm">
      <span style={{ color: s.color }} className="translate-y-[2px]"><Glyph glyph={s.glyph} size={12} /></span>
      <span className="min-w-0 break-words text-ink">{e.name}</span>
      {e.kind && e.kind !== e.type ? <span className="text-xs text-muted">{humanize(e.kind)}</span> : null}
      {e.line ? <span className="num ml-auto text-xs text-muted">line {e.line}</span> : null}
    </li>
  );
}

function Ref({ r, onOpenFile }: { r: NodeRef; onOpenFile: (id: string) => void }) {
  const s = NODE_STYLES[r.type];
  const label = r.qualifiedName ?? r.name;
  let body: React.ReactNode;
  if (r.external) body = <span className="num break-all text-xs text-ink-2">{label}</span>;
  else if (r.type === 'FILE') body = <button type="button" className="link num break-all text-left text-xs" onClick={() => onOpenFile(r.id)}>{r.file ?? r.name}</button>;
  else if (r.type === 'CLASS' || r.type === 'INTERFACE') body = <Link className="link num break-all text-xs" to={`../relationships?item=${encodeURIComponent(r.id)}`}>{label}</Link>;
  else body = <span className="num break-all text-xs text-ink">{label}</span>;
  return (
    <li className="flex items-baseline gap-xs">
      <span style={{ color: s.color }} className="translate-y-[2px]"><Glyph glyph={s.glyph} size={12} /></span>
      <span className="min-w-0">{body}</span>
      {r.external ? <span className="chip ml-auto" data-tone="quiet">External</span> : null}
    </li>
  );
}

/** The file's declarations, imports and references, from GET /files/{id}. Internal imports open their file. */
export function FileDrawer({ analysisId, fileId, onClose, onOpenFile }: { analysisId: string; fileId: string; onClose: () => void; onOpenFile: (id: string) => void }) {
  const file = useFile(analysisId, fileId, true);
  return (
    <aside className="inspector" aria-label="File details">
      <div className="flex items-start justify-between gap-sm">
        <h2 className="min-w-0 break-words text-lg">{file.data?.name ?? fileId.replace(/^file:/, '').split('/').pop()}</h2>
        <button type="button" className="btn btn-quiet btn-icon -mr-xs" onClick={onClose} aria-label="Close file details">
          <X size={18} aria-hidden="true" />
        </button>
      </div>
      {file.isPending ? (
        <div className="grid gap-sm" role="status" aria-label="Loading the file">
          <Skeleton className="h-4 w-3/4" />
          <Skeleton className="h-24 w-full" />
        </div>
      ) : file.isError ? (
        hasCode(file.error, 'NODE_NOT_FOUND') ? <p className="text-sm text-muted">This file is not part of the analysed repository.</p> : <ErrorState compact error={file.error} onRetry={() => void file.refetch()} />
      ) : (
        <>
          <dl className="grid gap-sm text-sm">
            <div className="grid gap-3xs"><dt className="text-muted">Path</dt><dd className="num break-all text-xs text-ink">{file.data.path}</dd></div>
            <div className="grid gap-3xs"><dt className="text-muted">Language</dt><dd className="text-ink">{file.data.language}</dd></div>
            {file.data.module ? <div className="grid gap-3xs"><dt className="text-muted">Module</dt><dd className="num break-all text-xs text-ink">{file.data.module.name}</dd></div> : null}
          </dl>
          <Section title="Classes" count={file.data.classes.length}>{file.data.classes.map((e) => <Entity key={e.id} e={e} />)}</Section>
          <Section title="Interfaces" count={file.data.interfaces.length}>{file.data.interfaces.map((e) => <Entity key={e.id} e={e} />)}</Section>
          <Section title="Methods" count={file.data.methods.length}>{file.data.methods.map((e) => <Entity key={e.id} e={e} />)}</Section>
          <Section title="Functions" count={file.data.functions.length}>{file.data.functions.map((e) => <Entity key={e.id} e={e} />)}</Section>
          <Section title="Imports" count={file.data.imports.length}>{file.data.imports.map((r) => <Ref key={r.id} r={r} onOpenFile={onOpenFile} />)}</Section>
          <Section title="References" count={file.data.references.length}>{file.data.references.map((r) => <Ref key={r.id} r={r} onOpenFile={onOpenFile} />)}</Section>
        </>
      )}
    </aside>
  );
}
