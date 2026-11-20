import { useState } from 'react';
import { useNavigate } from 'react-router';
import { MoreHorizontal, RefreshCw, Trash2 } from 'lucide-react';
import { hasCode } from '@/api/errors';
import { isNotAnalysed, isRunning, type AnalysisSummary } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Menu } from '@/components/ui/Menu';
import { Notice } from '@/components/ui/Notice';
import { useAnalysisUsage } from '@/hooks/useAccount';
import { useDeleteAnalysis, useReanalyze } from '@/hooks/useAnalyses';
import { describeError } from '@/lib/errorCopy';
import { creditReturnNote } from '@/lib/restoredEntry';
import { repoLabel } from '@/lib/url';

/** Re-run an analysis. The new run gets a new id; it replaces this one only when it completes. */
export function ReanalyzeDialog({ analysis, open, onClose }: { analysis: Pick<AnalysisSummary, 'analysisId' | 'repositoryUrl'>; open: boolean; onClose: () => void }) {
  const reanalyze = useReanalyze();
  const navigate = useNavigate();
  const { plan } = useAnalysisUsage();
  const copy = reanalyze.error ? describeError(reanalyze.error) : null;
  return (
    <Dialog
      open={open}
      onClose={() => { reanalyze.reset(); onClose(); }}
      title={`Re-analyze ${repoLabel(analysis.repositoryUrl)}?`}
      footer={
        <>
          <Button variant="quiet" onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={reanalyze.isPending}
            icon={<RefreshCw size={16} aria-hidden="true" />}
            onClick={() => reanalyze.mutate(analysis.analysisId, { onSuccess: (res) => { onClose(); navigate(`/analyses/${res.analysisId}`); } })}
          >
            Re-analyze
          </Button>
        </>
      }
    >
      <div className="grid gap-sm">
        <p>Archyra analyses the latest code as a new run. This analysis stays until the new one completes, then the new one replaces it. If the new run fails, this one is kept.</p>
        {plan === 'FREE' ? <p className="text-muted">On the free plan the run needs one free analysis while it works; you get it back when it replaces this one.</p> : null}
        <p>Ask AI conversations for the current analysis are deleted when the new analysis completes.</p>
        {copy ? <Notice tone="error" title={copy.title}>{copy.message}</Notice> : null}
      </div>
    </Dialog>
  );
}

export function DeleteDialog({ analysis, open, onClose, onDeleted }: { analysis: Pick<AnalysisSummary, 'analysisId' | 'repositoryUrl' | 'status'>; open: boolean; onClose: () => void; onDeleted?: () => void }) {
  const remove = useDeleteAnalysis();
  const { plan, entry } = useAnalysisUsage();
  const notAnalysed = isNotAnalysed(analysis.status);
  const running = isRunning(analysis.status) || hasCode(remove.error, 'ANALYSIS_IN_PROGRESS');
  const copy = remove.error && !hasCode(remove.error, 'ANALYSIS_IN_PROGRESS') ? describeError(remove.error) : null;
  return (
    <Dialog
      open={open}
      onClose={() => { remove.reset(); onClose(); }}
      title={`Delete ${repoLabel(analysis.repositoryUrl)}?`}
      footer={
        <>
          <Button variant="quiet" onClick={onClose}>Cancel</Button>
          <Button
            variant="danger"
            loading={remove.isPending}
            disabled={running}
            icon={<Trash2 size={16} aria-hidden="true" />}
            onClick={() => remove.mutate(analysis.analysisId, { onSuccess: () => { onClose(); onDeleted?.(); } })}
          >
            {notAnalysed ? 'Delete repository' : 'Delete analysis'}
          </Button>
        </>
      }
    >
      <div className="grid gap-sm">
        {running ? (
          <Notice tone="warn" title="This analysis is still running">A running analysis cannot be deleted. Wait until it completes or fails.</Notice>
        ) : notAnalysed ? (
          <p>This repository has not been analysed, so there is no graph to delete. The saved link is removed. This cannot be undone.</p>
        ) : (
          <p>The graph, files and relationships of this analysis are deleted. This cannot be undone.</p>
        )}
        {!running && notAnalysed ? <p className="text-muted">{creditReturnNote(plan, entry)}</p> : null}
        {!running && !notAnalysed && plan === 'FREE' ? <p className="text-muted">On the free plan, deleting it gives you back one free analysis.</p> : null}
        {copy ? <Notice tone="error" title={copy.title}>{copy.message}</Notice> : null}
      </div>
    </Dialog>
  );
}

/** The overflow menu on a history row or the repository header. */
export function AnalysisMenu({ analysis, onDeleted }: { analysis: AnalysisSummary; onDeleted?: () => void }) {
  const [dialog, setDialog] = useState<'reanalyze' | 'delete' | null>(null);
  const label = repoLabel(analysis.repositoryUrl);
  return (
    <>
      <Menu
        trigger={({ ref, ...props }) => (
          <button ref={ref} type="button" className="btn btn-quiet btn-icon" aria-label={`More actions for ${label}`} {...props}>
            <MoreHorizontal size={18} aria-hidden="true" />
          </button>
        )}
      >
        {(close) => (
          <>
            <button type="button" role="menuitem" className="menu-item" disabled={isRunning(analysis.status)} onClick={() => { close(); setDialog('reanalyze'); }}>
              <RefreshCw size={16} aria-hidden="true" /> Re-analyze
            </button>
            <button type="button" role="menuitem" className="menu-item" data-tone="danger" onClick={() => { close(); setDialog('delete'); }}>
              <Trash2 size={16} aria-hidden="true" /> Delete
            </button>
          </>
        )}
      </Menu>
      <ReanalyzeDialog analysis={analysis} open={dialog === 'reanalyze'} onClose={() => setDialog(null)} />
      <DeleteDialog analysis={analysis} open={dialog === 'delete'} onClose={() => setDialog(null)} onDeleted={onDeleted} />
    </>
  );
}
