import { useLocation, useNavigate } from 'react-router';
import { CircleDashed } from 'lucide-react';
import { EmptyState } from '@/components/ui/States';
import { RestoredEntryActions } from '@/features/dashboard/RestoredEntryActions';
import { ASK_AI_DISABLED_NOTE, RESTORED_NOTE } from '@/lib/restoredEntry';

const WHAT_APPEARS: Record<string, string> = {
  overview: 'The overview, with its languages, technologies and a preview of the graph, appears once this repository has been analysed.',
  architecture: 'The architecture graph appears once this repository has been analysed.',
  files: 'The file list appears once this repository has been analysed.',
  relationships: 'Classes, interfaces and their relationships appear once this repository has been analysed.',
  'ask-ai': ASK_AI_DISABLED_NOTE,
};

/**
 * What every tab of a restored (NOT_ANALYZED) repository shows instead of its content: no graph, no spinner, no request to a
 * results endpoint (those answer "not analysed" for it), and the two actions the entry offers.
 */
export function NotAnalysedPanel({ analysisId, repositoryUrl }: { analysisId: string; repositoryUrl: string }) {
  const navigate = useNavigate();
  const tab = useLocation().pathname.split('/').filter(Boolean).pop() ?? 'overview';
  return (
    <div className="px-md py-lg sm:px-lg" data-testid="not-analysed-panel">
      <EmptyState
        title="Not analysed yet"
        icon={<CircleDashed size={28} aria-hidden="true" />}
        action={<RestoredEntryActions entry={{ analysisId, repositoryUrl }} size="md" onDeleted={() => navigate('/dashboard', { replace: true })} />}
      >
        <p>{RESTORED_NOTE}</p>
        <p className="mt-xs">{WHAT_APPEARS[tab] ?? WHAT_APPEARS.overview}</p>
      </EmptyState>
    </div>
  );
}
