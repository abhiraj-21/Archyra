import { useState } from 'react';
import { Navigate, NavLink, Outlet, useMatch, useNavigate, useParams } from 'react-router';
import { Boxes, FileCode2, GitBranch, LayoutGrid, MessageSquareText, MoreHorizontal, RefreshCw, Trash2 } from 'lucide-react';
import { hasCode } from '@/api/errors';
import { Button } from '@/components/ui/Button';
import { StatusChip } from '@/components/ui/Chips';
import { Menu } from '@/components/ui/Menu';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useAnalysesPage, useAnalysisStatus, useKnownRepositoryUrl } from '@/hooks/useAnalyses';
import { useRepository } from '@/hooks/useWorkspace';
import { repoLabel, safeGithubHref } from '@/lib/url';
import { NotFoundPage } from '@/features/system/NotFoundPage';
import { DeleteDialog, ReanalyzeDialog } from '@/features/dashboard/AnalysisActions';
import { NotAnalysedPanel } from './NotAnalysedPanel';
import type { WorkspaceContext } from './workspaceContext';

const TABS = [
  { to: 'overview', label: 'Overview', icon: LayoutGrid },
  { to: 'architecture', label: 'Architecture', icon: Boxes },
  { to: 'files', label: 'Files', icon: FileCode2 },
  { to: 'relationships', label: 'Relationships', icon: GitBranch },
  { to: 'ask-ai', label: 'Ask AI', icon: MessageSquareText },
] as const;

/**
 * /analyses/:analysisId/<tab>: a COMPLETED analysis shows its results; a restored NOT_ANALYZED entry shows the same header and
 * tabs with the "not analysed" panel on every tab (nothing is requested for it); anything else goes to the progress view.
 */
export default function WorkspaceLayout() {
  const { analysisId = '' } = useParams();
  const navigate = useNavigate();
  const status = useAnalysisStatus(analysisId);
  useAnalysesPage(0); // fills the history cache, which knows the repository URL
  const url = useKnownRepositoryUrl(analysisId);
  const ready = status.data?.status === 'COMPLETED';
  const notAnalysed = status.data?.status === 'NOT_ANALYZED';
  const repository = useRepository(analysisId, ready);
  const [dialog, setDialog] = useState<'reanalyze' | 'delete' | null>(null);
  // The graph fills the viewport on wide screens; other tabs scroll as pages.
  const fillViewport = useMatch('/analyses/:analysisId/architecture') !== null && !notAnalysed;

  if (status.isPending) {
    return (
      <div className="grid gap-md px-md py-lg sm:px-lg" role="status" aria-label="Loading the analysis">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-11 w-full" />
      </div>
    );
  }
  if (status.isError && !status.data) {
    if (hasCode(status.error, 'ANALYSIS_NOT_FOUND')) {
      return <NotFoundPage inShell title="Analysis not found" message="It may have been deleted or replaced by a newer run, or the link is wrong." />;
    }
    return (
      <div className="px-md sm:px-lg">
        <ErrorState error={status.error} onRetry={() => void status.refetch()} />
      </div>
    );
  }
  if (!ready && !notAnalysed) return <Navigate to={`/analyses/${analysisId}`} replace />;

  const name = url ? repoLabel(url) : (repository.data?.name ?? 'Repository');
  const href = url ? safeGithubHref(url) : null;
  const summary = { analysisId, repositoryUrl: url ?? name, status: 'COMPLETED' as const };
  const context: WorkspaceContext = { analysisId, repositoryUrl: summary.repositoryUrl, ready: true };

  return (
    <div className={`flex flex-col ${fillViewport ? 'lg:h-dvh lg:overflow-hidden' : 'min-h-dvh max-lg:min-h-[calc(100dvh-3.5rem)]'}`}>
      <header className="grid gap-sm border-b border-rule bg-paper px-md pt-md sm:px-lg">
        <div className="flex flex-wrap items-start justify-between gap-md">
          <div className="grid min-w-0 gap-2xs">
            <div className="flex flex-wrap items-center gap-sm">
              <h1 className="min-w-0 max-w-none truncate text-xl">{name}</h1>
              <StatusChip status={notAnalysed ? 'NOT_ANALYZED' : 'COMPLETED'} />
            </div>
            <div className="flex flex-wrap items-center gap-xs text-sm text-muted">
              {href ? (
                <a href={href} target="_blank" rel="noopener noreferrer" className="link-plain truncate">
                  {href.replace('https://', '')}
                </a>
              ) : null}
              {repository.data?.languages.slice(0, 6).map((lang) => (
                <span key={lang} className="chip">
                  {lang}
                </span>
              ))}
              {repository.data && repository.data.languages.length > 6 ? <span className="text-xs">and {repository.data.languages.length - 6} more</span> : null}
            </div>
          </div>
          {notAnalysed ? null : (
          <div className="flex items-center gap-xs">
            <Button size="sm" icon={<RefreshCw size={14} aria-hidden="true" />} onClick={() => setDialog('reanalyze')}>
              Re-analyze
            </Button>
            <Menu
              trigger={({ ref, ...props }) => (
                <button ref={ref} type="button" className="btn btn-quiet btn-icon" aria-label="More actions" {...props}>
                  <MoreHorizontal size={18} aria-hidden="true" />
                </button>
              )}
            >
              {(close) => (
                <button type="button" role="menuitem" className="menu-item" data-tone="danger" onClick={() => { close(); setDialog('delete'); }}>
                  <Trash2 size={16} aria-hidden="true" /> Delete analysis
                </button>
              )}
            </Menu>
          </div>
          )}
        </div>
        <nav aria-label="Repository sections" className="tabbar -mx-md px-md sm:-mx-lg sm:px-lg">
          {TABS.map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} className="tab">
              <Icon size={16} aria-hidden="true" />
              {label}
            </NavLink>
          ))}
        </nav>
      </header>
      <div className="flex min-h-0 flex-1 flex-col">
        {notAnalysed ? <NotAnalysedPanel analysisId={analysisId} repositoryUrl={summary.repositoryUrl} /> : <Outlet context={context} />}
      </div>
      {notAnalysed ? null : (
        <>
          <ReanalyzeDialog analysis={summary} open={dialog === 'reanalyze'} onClose={() => setDialog(null)} />
          <DeleteDialog analysis={summary} open={dialog === 'delete'} onClose={() => setDialog(null)} onDeleted={() => navigate('/dashboard', { replace: true })} />
        </>
      )}
    </div>
  );
}
