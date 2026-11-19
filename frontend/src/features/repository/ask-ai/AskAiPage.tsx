import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useAiIndex } from '@/hooks/useAskAi';
import { useWorkspace } from '../workspaceContext';
import { ChatWorkspace } from './ChatWorkspace';
import { Composer } from './Composer';
import { IndexPanel } from './IndexPanel';

/** Ask AI: the index state decides what is shown (there is no chat until it is READY). */
export default function AskAiPage() {
  useDocumentTitle('Ask AI');
  const { analysisId, repositoryUrl, ready } = useWorkspace();
  const index = useAiIndex(analysisId, ready);

  if (index.isPending) {
    return (
      <div className="mx-auto grid w-full max-w-[40rem] gap-md px-md py-xl" role="status" aria-label="Checking Ask AI">
        <Skeleton className="h-8 w-72" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }
  if (index.isError && !index.data) {
    return (
      <div className="px-md sm:px-lg">
        <ErrorState error={index.error} onRetry={() => void index.refetch()} />
      </div>
    );
  }
  const state = index.data;
  if (!state) return null;

  if (state.status === 'READY') return <ChatWorkspace analysisId={analysisId} repositoryUrl={repositoryUrl} index={state} />;
  return (
    <div className="grid gap-lg px-md py-xl sm:px-lg">
      <IndexPanel analysisId={analysisId} repositoryUrl={repositoryUrl} index={state} />
      {state.status === 'INDEXING' ? (
        <div className="mx-auto w-full max-w-[40rem]">
          <Composer value="" onChange={() => undefined} onSend={() => undefined} disabledReason="Ask AI is being prepared for this repository. You can ask as soon as it is ready." sending={false} />
        </div>
      ) : null}
    </div>
  );
}
