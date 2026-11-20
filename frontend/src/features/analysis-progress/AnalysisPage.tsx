import { useCallback } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router';
import { hasCode } from '@/api/errors';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useAnalysesPage, useAnalysisStatus, useKnownRepositoryUrl } from '@/hooks/useAnalyses';
import { NotFoundPage } from '@/features/system/NotFoundPage';
import { ProgressView } from './ProgressView';
import { useCompletionMoment } from './useCompletionMoment';

/** /analyses/:analysisId — progress while running; a COMPLETED analysis goes to its overview. */
export default function AnalysisPage() {
  const { analysisId = '' } = useParams();
  const navigate = useNavigate();
  const status = useAnalysisStatus(analysisId);
  useAnalysesPage(0); // the newest history page knows the repository URL of a run that just started
  const repositoryUrl = useKnownRepositoryUrl(analysisId);
  const goToOverview = useCallback(() => navigate(`/analyses/${analysisId}/overview`, { replace: true }), [analysisId, navigate]);
  const moment = useCompletionMoment(status.data?.status, goToOverview);

  if (status.isPending) {
    return (
      <div className="mx-auto grid w-full max-w-[44rem] gap-md px-md py-xl sm:px-lg" role="status" aria-label="Loading the analysis">
        <Skeleton className="h-6 w-28" />
        <Skeleton className="h-9 w-3/4" />
        <Skeleton className="h-2 w-full" />
      </div>
    );
  }
  if (status.isError && !status.data) {
    // Unknown, malformed and other people's ids all answer 404: never imply the analysis exists.
    if (hasCode(status.error, 'ANALYSIS_NOT_FOUND')) {
      return <NotFoundPage inShell title="Analysis not found" message="It may have been deleted or replaced by a newer run, or the link is wrong." />;
    }
    return (
      <div className="mx-auto w-full max-w-[44rem] px-md sm:px-lg">
        <ErrorState error={status.error} onRetry={() => void status.refetch()} />
      </div>
    );
  }
  // A restored entry has nothing to show progress for: its workspace explains it and offers Analyse and Delete.
  if (status.data?.status === 'NOT_ANALYZED') return <Navigate to={`/analyses/${analysisId}/overview`} replace />;
  if (status.data?.status === 'COMPLETED' && !moment) return <Navigate to={`/analyses/${analysisId}/overview`} replace />;
  return status.data ? <ProgressView status={status.data} repositoryUrl={repositoryUrl} justCompleted={moment} /> : null;
}
