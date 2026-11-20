import { useEffect, useRef } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient, type Query } from '@tanstack/react-query';
import { analyzeRestored, createAnalysis, deleteAnalysis, getAnalysisStatus, listAnalyses, reanalyze } from '@/api/endpoints/analyses';
import { queryKeys } from '@/api/queryKeys';
import { isRunning, isSettled, isTerminal, type AnalysisStatusResponse } from '@/api/types';

export const HISTORY_PAGE_SIZE = 20;
const POLL_MS = 2000;

export function useAnalysesPage(page: number) {
  return useQuery({
    queryKey: queryKeys.analysesPage(page, HISTORY_PAGE_SIZE),
    queryFn: ({ signal }) => listAnalyses(page, HISTORY_PAGE_SIZE, signal),
    placeholderData: keepPreviousData,
    // History shows live progress for running analyses.
    refetchInterval: (query) => (query.state.data?.items.some((i) => isRunning(i.status)) ? 4000 : false),
  });
}

/** Poll interval: 2s while running, backing off on errors, stopped at COMPLETED/FAILED and for a NOT_ANALYZED entry. Hidden tabs never poll. */
export function statusPollInterval(query: Pick<Query<AnalysisStatusResponse>, 'state'>): number | false {
  const status = query.state.data?.status;
  if (status && isSettled(status)) return false;
  if (query.state.status === 'error' && query.state.error && 'status' in query.state.error && (query.state.error as { status: number }).status === 404) {
    return false;
  }
  const failures = query.state.fetchFailureCount;
  return failures > 0 ? Math.min(POLL_MS * 2 ** failures, 30_000) : POLL_MS;
}

export function useAnalysisStatus(analysisId: string) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: queryKeys.analysisStatus(analysisId),
    queryFn: ({ signal }) => getAnalysisStatus(analysisId, signal),
    refetchInterval: statusPollInterval,
    refetchIntervalInBackground: false,
    staleTime: 0,
    retry: false,
  });

  // A terminal state changes history and usage (a FAILED run has its credit returned): refetch both once.
  const status = query.data?.status;
  const seen = useRef<string | null>(null);
  useEffect(() => {
    if (!status || !isTerminal(status) || seen.current === status) return;
    seen.current = status;
    void queryClient.invalidateQueries({ queryKey: queryKeys.analyses });
    void queryClient.invalidateQueries({ queryKey: queryKeys.usage });
  }, [status, queryClient]);

  return query;
}

function useInvalidateHistoryAndUsage() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.analyses }),
      queryClient.invalidateQueries({ queryKey: queryKeys.usage }),
    ]);
}

export function useCreateAnalysis() {
  const invalidate = useInvalidateHistoryAndUsage();
  return useMutation({ mutationFn: (repositoryUrl: string) => createAnalysis(repositoryUrl), onSettled: invalidate });
}

/**
 * The deleted analysis's own cache is left to expire rather than removed here: removing it would unmount the
 * workspace (and the dialog's navigate-away callback with it) before the user leaves the page.
 */
export function useDeleteAnalysis() {
  const invalidate = useInvalidateHistoryAndUsage();
  return useMutation({ mutationFn: (analysisId: string) => deleteAnalysis(analysisId), onSettled: invalidate });
}

/**
 * Analyses a restored entry with the credit it already holds. The count does not change, but the usage is refetched anyway
 * (the server's answer is the truth), and the status query is set to QUEUED at once so polling resumes and the progress view
 * does not send the visitor back to the "not analysed" screen. `onStarted` runs right after, before the lists refetch.
 */
export function useAnalyzeRestored(onStarted?: (analysisId: string) => void) {
  const queryClient = useQueryClient();
  const invalidate = useInvalidateHistoryAndUsage();
  return useMutation({
    mutationFn: (analysisId: string) => analyzeRestored(analysisId),
    onSuccess: (res) => {
      queryClient.setQueryData<AnalysisStatusResponse>(queryKeys.analysisStatus(res.analysisId), { analysisId: res.analysisId, status: res.status, progress: 0 });
      void queryClient.invalidateQueries({ queryKey: queryKeys.analysisStatus(res.analysisId) });
      // Here, not in `mutate(..., { onSuccess })`: the history refetch that follows replaces the row that called this, and a
      // per-call callback of an unmounted component never runs.
      onStarted?.(res.analysisId);
    },
    onSettled: invalidate,
  });
}

export function useReanalyze() {
  const invalidate = useInvalidateHistoryAndUsage();
  return useMutation({ mutationFn: (analysisId: string) => reanalyze(analysisId), onSettled: invalidate });
}

/** The repository URL of an analysis, from any history page already loaded (the status endpoint has no URL). */
export function useKnownRepositoryUrl(analysisId: string): string | undefined {
  const queryClient = useQueryClient();
  for (const [, page] of queryClient.getQueriesData<{ items: { analysisId: string; repositoryUrl: string }[] }>({ queryKey: queryKeys.analyses })) {
    const hit = page?.items?.find((i) => i.analysisId === analysisId);
    if (hit) return hit.repositoryUrl;
  }
  return undefined;
}
