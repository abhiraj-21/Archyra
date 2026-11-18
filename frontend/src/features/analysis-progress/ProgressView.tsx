import { useNavigate } from 'react-router';
import { ArrowLeft, Check, RotateCw } from 'lucide-react';
import type { AnalysisStatus, AnalysisStatusResponse } from '@/api/types';
import { Button, ButtonLink } from '@/components/ui/Button';
import { StatusChip } from '@/components/ui/Chips';
import { Notice } from '@/components/ui/Notice';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useAnalysisUsage } from '@/hooks/useAccount';
import { useReanalyze } from '@/hooks/useAnalyses';
import { describeError } from '@/lib/errorCopy';
import { repoLabel } from '@/lib/url';
import { GrowingStructure } from './GrowingStructure';

const STEPS: { status: AnalysisStatus; title: string; text: string }[] = [
  { status: 'QUEUED', title: 'Queued', text: 'Waiting for a free worker.' },
  { status: 'CLONING', title: 'Cloning', text: 'Downloading the latest code from GitHub.' },
  { status: 'PARSING', title: 'Parsing', text: 'Reading each source file with Tree-sitter.' },
  { status: 'ANALYZING', title: 'Analysing', text: 'Resolving imports, calls and inheritance across files.' },
  { status: 'BUILDING_GRAPH', title: 'Building the graph', text: 'Writing classes, files and their relationships.' },
];

function stepState(index: number, current: AnalysisStatus): 'done' | 'active' | 'todo' {
  if (current === 'COMPLETED') return 'done';
  const at = STEPS.findIndex((s) => s.status === current);
  if (index < at) return 'done';
  return index === at ? 'active' : 'todo';
}

export function ProgressView({ status, repositoryUrl, justCompleted }: { status: AnalysisStatusResponse; repositoryUrl?: string; justCompleted: boolean }) {
  const name = repositoryUrl ? repoLabel(repositoryUrl) : 'your repository';
  useDocumentTitle(status.status === 'FAILED' ? 'Analysis failed' : `Analysing ${name}`);
  const active = STEPS.find((s) => s.status === status.status);

  if (status.status === 'FAILED') return <FailedView status={status} name={name} />;

  return (
    <div className="mx-auto grid w-full max-w-[44rem] gap-xl px-md py-xl sm:px-lg">
      <header className="grid gap-xs">
        <div>
          <StatusChip status={status.status} progress={status.progress} />
        </div>
        <h1 className="max-w-none text-2xl">{justCompleted ? `${name} is ready` : `Analysing ${name}`}</h1>
        <p className="text-muted" aria-live="polite">
          {justCompleted ? 'Opening the overview.' : active ? active.text : ''}
        </p>
      </header>

      <div className="grid gap-xs">
        <div className="flex items-baseline justify-between text-sm">
          <span className="font-medium text-ink-2">Progress</span>
          <span className="num text-ink-2">{status.progress}%</span>
        </div>
        <div className="meter" role="progressbar" aria-label="Analysis progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={status.progress} aria-valuetext={`${status.progress}%, ${active?.title ?? 'complete'}`}>
          <span style={{ transform: `scaleX(${status.progress / 100})` }} />
        </div>
      </div>

      <div className="grid gap-lg md:grid-cols-[minmax(0,1fr)_14rem] md:items-center">
        <ol className="stepper" aria-label="Analysis stages">
          {STEPS.map((step, i) => {
            const state = stepState(i, status.status);
            return (
              <li key={step.status} className="step" data-state={state} aria-current={state === 'active' ? 'step' : undefined}>
                <span className="step-mark">{state === 'done' ? <Check size={14} aria-hidden="true" /> : <span className="num">{i + 1}</span>}</span>
                <span className="grid gap-3xs pt-3xs">
                  <span className="font-medium">{step.title}</span>
                  {state === 'active' ? <span className="text-sm text-muted">{step.text}</span> : null}
                  <span className="sr-only">{state === 'done' ? 'done' : state === 'active' ? 'in progress' : 'not started'}</span>
                </span>
              </li>
            );
          })}
        </ol>
        <div className="max-md:hidden">{justCompleted ? null : <GrowingStructure />}</div>
      </div>

      <p className="text-sm text-muted">You can leave this page. The analysis keeps running and shows up in your history.</p>
    </div>
  );
}

function FailedView({ status, name }: { status: AnalysisStatusResponse; name: string }) {
  const reanalyze = useReanalyze();
  const navigate = useNavigate();
  const { plan } = useAnalysisUsage();
  const error = reanalyze.error ? describeError(reanalyze.error) : null;
  return (
    <div className="mx-auto grid w-full max-w-[44rem] gap-lg px-md py-xl sm:px-lg">
      <header className="grid gap-xs">
        <div>
          <StatusChip status="FAILED" />
        </div>
        <h1 className="text-2xl">The analysis of {name} failed</h1>
      </header>
      <Notice tone="error" title="What happened">
        {status.failureReason ?? 'The analysis stopped before it finished.'}
      </Notice>
      {plan === 'FREE' ? <p className="text-muted">A failed analysis does not count: its free credit has been returned.</p> : null}
      {error ? <Notice tone="error" title={error.title}>{error.message}</Notice> : null}
      <div className="flex flex-wrap gap-sm">
        <Button
          variant="primary"
          loading={reanalyze.isPending}
          icon={<RotateCw size={16} aria-hidden="true" />}
          onClick={() => reanalyze.mutate(status.analysisId, { onSuccess: (res) => navigate(`/analyses/${res.analysisId}`, { replace: true }) })}
        >
          Try again
        </Button>
        <ButtonLink to="/dashboard" icon={<ArrowLeft size={16} aria-hidden="true" />}>
          Back to dashboard
        </ButtonLink>
      </div>
    </div>
  );
}
