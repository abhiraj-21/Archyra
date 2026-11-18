import { useEffect, useId, useState } from 'react';
import { useSearchParams } from 'react-router';
import { ChevronDown, FileSearch } from 'lucide-react';
import { ScopeControl } from '@/components/graph/ScopeControl';
import { parseScope, SCOPE_DEFAULTS, scopeParam, type Scope } from '@/components/graph/scope';
import { Pagination } from '@/components/ui/Pagination';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useFiles, useRepository } from '@/hooks/useWorkspace';
import { useWorkspace } from '../workspaceContext';
import { FileDrawer } from './FileDrawer';

const SIZES = [20, 50, 100, 200];

export default function FilesPage() {
  useDocumentTitle('Files');
  const { analysisId, ready } = useWorkspace();
  const [params, setParams] = useSearchParams();
  const language = params.get('language') ?? '';
  const path = params.get('path') ?? '';
  const scope = parseScope(params.get('scope'), SCOPE_DEFAULTS.files);
  const page = Math.max(0, Number(params.get('page')) || 0);
  const sizeParam = Number(params.get('size'));
  const size = SIZES.includes(sizeParam) ? sizeParam : 50;
  const fileId = params.get('file');
  const [pathDraft, setPathDraft] = useState(path);
  const ids = { language: useId(), path: useId(), size: useId() };

  const repository = useRepository(analysisId, ready);
  // working sends test=false, tests sends test=true, all sends nothing; the server filters before it pages.
  const test = scope === 'working' ? false : scope === 'tests' ? true : undefined;
  const files = useFiles(analysisId, ready, { language: language || undefined, path: path || undefined, test, page, size });

  const update = (changes: Record<string, string | null>, resetPage = true) =>
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      for (const [k, v] of Object.entries(changes)) {
        if (v === null || v === '') next.delete(k);
        else next.set(k, v);
      }
      if (resetPage && !('page' in changes)) next.delete('page');
      return next;
    });

  const setScope = (next: Scope) => update({ scope: scopeParam(next, SCOPE_DEFAULTS.files), file: null });

  // Path filter: applied 300 ms after typing stops.
  useEffect(() => {
    if (pathDraft === path) return;
    const id = window.setTimeout(() => update({ path: pathDraft.trim() }), 300);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathDraft]);

  return (
    <div className="flex min-h-0 flex-1">
      <div className="mx-auto grid w-full max-w-[72rem] content-start gap-md px-md py-lg sm:px-lg">
        <div className="grid gap-sm sm:grid-cols-[14rem_minmax(0,1fr)_8rem]">
          <div className="field">
            <label htmlFor={ids.language} className="field-label">Language</label>
            <div className="select-wrap">
              <select id={ids.language} className="input" value={language} onChange={(e) => update({ language: e.target.value, file: null })}>
                <option value="">All languages</option>
                {repository.data?.languages.map((l) => <option key={l} value={l}>{l}</option>)}
              </select>
              <ChevronDown size={16} aria-hidden="true" />
            </div>
          </div>
          <div className="field">
            <label htmlFor={ids.path} className="field-label">Folder or file path</label>
            <input id={ids.path} className="input num" placeholder="src/main" value={pathDraft} onChange={(e) => setPathDraft(e.target.value)} maxLength={500} spellCheck={false} autoCapitalize="none" />
          </div>
          <div className="field">
            <label htmlFor={ids.size} className="field-label">Per page</label>
            <div className="select-wrap">
              <select id={ids.size} className="input" value={size} onChange={(e) => update({ size: e.target.value })}>
                {SIZES.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
              <ChevronDown size={16} aria-hidden="true" />
            </div>
          </div>
        </div>
        <ScopeControl value={scope} onChange={setScope} />
        <p className="text-xs text-muted">Paths match whole folders: src/main does not match src/mainframe. Languages that are only counted (SQL, shell and others) have no files here.</p>

        {files.isPending ? (
          <SkeletonRows rows={8} label="Loading files" />
        ) : files.isError ? (
          <ErrorState error={files.error} onRetry={() => void files.refetch()} />
        ) : files.data.totalElements === 0 ? (
          <EmptyState title="No files match" icon={<FileSearch size={28} aria-hidden="true" />} action={language || path || scope !== SCOPE_DEFAULTS.files ? <button type="button" className="link" onClick={() => { setPathDraft(''); update({ language: null, path: null, scope: null }); }}>Clear the filters</button> : null}>
            {scope === 'tests' && !language && !path
              ? 'No test files found in this analysis.'
              : language || path || scope !== SCOPE_DEFAULTS.files
                ? 'Nothing in this analysis matches the language, path and scope above.'
                : 'This analysis has no files of an analysed language.'}
          </EmptyState>
        ) : (
          <>
            <table className="table" aria-busy={files.isFetching || undefined}>
              <caption className="sr-only">Files, sorted by path</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col" className="w-[52%] max-md:hidden">Path</th>
                  <th scope="col">Language</th>
                </tr>
              </thead>
              <tbody>
                {files.data.items.map((f) => (
                  <tr key={f.id} data-interactive aria-selected={f.id === fileId} onClick={() => update({ file: f.id }, false)}>
                    <td>
                      <button type="button" className="link-plain text-left font-medium text-ink" onClick={(e) => { e.stopPropagation(); update({ file: f.id }, false); }}>
                        {f.name}
                      </button>
                      {f.test ? <span className="chip ml-xs align-middle">Test</span> : null}
                      <span className="num block break-all text-xs text-muted md:hidden">{f.path}</span>
                    </td>
                    <td className="num max-w-0 truncate text-xs text-muted max-md:hidden" title={f.path}>{f.path}</td>
                    <td className="whitespace-nowrap text-ink-2">{f.language}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pagination page={files.data.page} size={size} totalElements={files.data.totalElements} totalPages={files.data.totalPages} onPage={(p) => update({ page: p === 0 ? null : String(p) }, false)} label="File pages" />
          </>
        )}
      </div>
      {fileId ? <FileDrawer analysisId={analysisId} fileId={fileId} onClose={() => update({ file: null }, false)} onOpenFile={(id) => update({ file: id }, false)} /> : null}
    </div>
  );
}
