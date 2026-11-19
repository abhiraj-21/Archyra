import { useState } from 'react';
import { RefreshCw, RotateCw } from 'lucide-react';
import type { AiIndexResponse } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { Spinner } from '@/components/ui/Spinner';
import { ReanalyzeDialog } from '@/features/dashboard/AnalysisActions';
import { useRetryAiIndex } from '@/hooks/useAskAi';
import { describeError } from '@/lib/errorCopy';
import { formatNumber } from '@/lib/format';

interface PanelProps {
  analysisId: string;
  repositoryUrl: string;
  index: AiIndexResponse;
}

/** The index states in which there is no chat yet: not indexed, indexing (shown above a disabled composer), failed, empty. */
export function IndexPanel({ analysisId, repositoryUrl, index }: PanelProps) {
  switch (index.status) {
    case 'NOT_INDEXED':
      return (
        <Panel title="Ask AI isn't enabled for this analysis yet">
          <p>Re-analyze the repository to enable it.</p>
          <ReanalyzeAction analysisId={analysisId} repositoryUrl={repositoryUrl} primary />
        </Panel>
      );
    case 'INDEXING':
      return <Indexing index={index} />;
    case 'FAILED':
      return <Failed analysisId={analysisId} repositoryUrl={repositoryUrl} index={index} />;
    case 'EMPTY':
      return (
        <Panel title="Nothing to ask about">
          <p>{index.reason ?? 'This repository has no non-test code to ask about.'}</p>
        </Panel>
      );
    case 'READY':
      return null;
  }
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="well mx-auto grid w-full max-w-[40rem] gap-sm p-lg" aria-labelledby="ai-index-title">
      <h2 id="ai-index-title" className="text-xl">{title}</h2>
      <div className="grid justify-items-start gap-sm text-muted">{children}</div>
    </section>
  );
}

function Indexing({ index }: { index: AiIndexResponse }) {
  const known = index.chunkCount > 0;
  const ratio = known ? Math.min(1, index.embeddedCount / index.chunkCount) : 0;
  const label = known ? `${formatNumber(index.embeddedCount)} of ${formatNumber(index.chunkCount)} code sections prepared` : 'Preparing Ask AI';
  return (
    <section className="well mx-auto grid w-full max-w-[40rem] gap-sm p-lg" aria-labelledby="ai-index-title">
      <h2 id="ai-index-title" className="text-xl">Preparing Ask AI for this repository…</h2>
      <div aria-live="polite" className="grid gap-xs">
        {known ? (
          <div className="meter" role="progressbar" aria-label="Preparing Ask AI" aria-valuemin={0} aria-valuemax={index.chunkCount} aria-valuenow={index.embeddedCount} aria-valuetext={label}>
            <span style={{ transform: `scaleX(${ratio})` }} />
          </div>
        ) : (
          <p className="inline-flex items-center gap-xs text-muted">
            <Spinner size={16} /> Reading the code…
          </p>
        )}
        <p className="text-sm text-muted">{label}. This runs in the background, and you can leave this page.</p>
      </div>
    </section>
  );
}

function Failed({ analysisId, repositoryUrl, index }: PanelProps) {
  const retry = useRetryAiIndex(analysisId);
  const copy = retry.error ? describeError(retry.error) : null;
  return (
    <Panel title="Ask AI could not be prepared">
      <p>{index.reason ?? 'Preparing this repository for questions failed.'}</p>
      {index.retryable ? (
        <>
          <p className="text-sm">Trying again is free and does not use a question.</p>
          <Button variant="primary" loading={retry.isPending} icon={<RotateCw size={16} aria-hidden="true" />} onClick={() => retry.mutate()}>
            Retry
          </Button>
        </>
      ) : (
        <ReanalyzeAction analysisId={analysisId} repositoryUrl={repositoryUrl} primary />
      )}
      {copy ? <Notice tone="error" title={copy.title}>{copy.message}</Notice> : null}
    </Panel>
  );
}

/** Opens the existing re-analyze dialog (the same one the workspace header uses), which carries the conversation warning. */
export function ReanalyzeAction({ analysisId, repositoryUrl, primary = false }: { analysisId: string; repositoryUrl: string; primary?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant={primary ? 'primary' : 'secondary'} icon={<RefreshCw size={16} aria-hidden="true" />} onClick={() => setOpen(true)}>
        Re-analyze
      </Button>
      <ReanalyzeDialog analysis={{ analysisId, repositoryUrl }} open={open} onClose={() => setOpen(false)} />
    </>
  );
}
