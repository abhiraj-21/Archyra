import { Link, useSearchParams } from 'react-router';
import { FolderGit2 } from 'lucide-react';
import { isNotAnalysed, isRunning, type AnalysisSummary } from '@/api/types';
import { ButtonLink } from '@/components/ui/Button';
import { StatusChip } from '@/components/ui/Chips';
import { Pagination } from '@/components/ui/Pagination';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { HISTORY_PAGE_SIZE, useAnalysesPage } from '@/hooks/useAnalyses';
import { formatDateTime } from '@/lib/format';
import { RESTORED_NOTE } from '@/lib/restoredEntry';
import { repoLabel, safeGithubHref } from '@/lib/url';
import { AnalysisMenu } from './AnalysisActions';
import { RestoredEntryActions } from './RestoredEntryActions';

function openPath(a: AnalysisSummary) {
  // A not-analysed entry has no progress to show: its workspace is the page with the two actions.
  return a.status === 'COMPLETED' || isNotAnalysed(a.status) ? `/analyses/${a.analysisId}/overview` : `/analyses/${a.analysisId}`;
}

function Row({ a }: { a: AnalysisSummary }) {
  const href = safeGithubHref(a.repositoryUrl);
  const running = isRunning(a.status);
  const idle = isNotAnalysed(a.status);
  return (
    <tr className="max-md:grid max-md:grid-cols-[minmax(0,1fr)_auto] max-md:gap-x-sm max-md:gap-y-2xs max-md:border-b max-md:border-rule max-md:py-sm">
      <td className="max-md:col-span-2 max-md:border-0 max-md:p-0">
        <Link to={openPath(a)} className="link-plain font-medium text-ink">
          {repoLabel(a.repositoryUrl)}
        </Link>
        {href ? (
          <a href={href} target="_blank" rel="noopener noreferrer" className="link-plain block truncate text-xs text-muted">
            {href.replace('https://', '')}
          </a>
        ) : null}
        {idle ? <p className="mt-2xs max-w-[52ch] text-xs text-muted">{RESTORED_NOTE}</p> : null}
      </td>
      <td className="max-md:border-0 max-md:p-0">
        <div className="grid justify-items-start gap-2xs">
          <StatusChip status={a.status} progress={a.progress} />
          {running ? (
            <div className="meter w-24" role="progressbar" aria-label={`${repoLabel(a.repositoryUrl)} progress`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={a.progress}>
              <span style={{ transform: `scaleX(${a.progress / 100})` }} />
            </div>
          ) : null}
        </div>
      </td>
      <td className="whitespace-nowrap text-muted max-md:col-span-2 max-md:row-start-3 max-md:border-0 max-md:p-0 max-md:text-xs">
        <span className="md:hidden">Started </span>
        <time dateTime={a.createdAt}>{formatDateTime(a.createdAt)}</time>
      </td>
      <td className="max-md:col-start-2 max-md:row-start-2 max-md:border-0 max-md:p-0">
        {idle ? (
          <RestoredEntryActions entry={a} />
        ) : (
          <div className="flex items-center justify-end gap-2xs">
            <ButtonLink to={openPath(a)} size="sm" variant={a.status === 'COMPLETED' ? 'secondary' : 'quiet'}>
              {a.status === 'COMPLETED' ? 'Open' : running ? 'View progress' : 'Details'}
            </ButtonLink>
            <AnalysisMenu analysis={a} />
          </div>
        )}
      </td>
    </tr>
  );
}

export function HistoryTable() {
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);
  const history = useAnalysesPage(page);

  const setPage = (p: number) => {
    const next = new URLSearchParams(params);
    if (p === 0) next.delete('page');
    else next.set('page', String(p));
    setParams(next);
  };

  if (history.isPending) return <SkeletonRows rows={4} label="Loading your analyses" />;
  if (history.isError) return <ErrorState error={history.error} onRetry={() => void history.refetch()} />;

  const data = history.data;
  if (data.totalElements === 0) {
    return (
      <EmptyState title="No analyses yet" icon={<FolderGit2 size={28} aria-hidden="true" />}>
        Paste a public GitHub repository above. Its graph, files and class relationships appear here when the analysis completes.
      </EmptyState>
    );
  }
  if (data.items.length === 0) {
    return (
      <EmptyState title="Nothing on this page" action={<button type="button" className="link" onClick={() => setPage(0)}>Back to the first page</button>}>
        The list changed since this page was opened.
      </EmptyState>
    );
  }

  return (
    <div className="grid gap-md">
      <table className="table max-md:block">
        <caption className="sr-only">Your analyses, newest first</caption>
        <thead className="max-md:sr-only">
          <tr>
            <th scope="col">Repository</th>
            <th scope="col">Status</th>
            <th scope="col">Started</th>
            <th scope="col">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody className="max-md:block">
          {data.items.map((a) => (
            <Row key={a.analysisId} a={a} />
          ))}
        </tbody>
      </table>
      <Pagination page={data.page} size={HISTORY_PAGE_SIZE} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} label="History pages" />
    </div>
  );
}
