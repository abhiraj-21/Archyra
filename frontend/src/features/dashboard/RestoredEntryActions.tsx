import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Play, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { useAnalyzeRestored } from '@/hooks/useAnalyses';
import { describeError } from '@/lib/errorCopy';
import { repoLabel } from '@/lib/url';
import { DeleteDialog } from './AnalysisActions';

interface Entry {
  analysisId: string;
  repositoryUrl: string;
}

/**
 * The two things a restored (not-analysed) repository offers. *Analyse* uses the credit it already holds (the server charges
 * nothing) and then follows the ordinary progress screen; *Delete* returns that credit to a free user, after the usual
 * confirmation. Used by the repository list and by the workspace of a not-analysed repository, so both behave the same.
 */
export function RestoredEntryActions({ entry, size = 'sm', onDeleted }: { entry: Entry; size?: 'sm' | 'md'; onDeleted?: () => void }) {
  const navigate = useNavigate();
  const analyse = useAnalyzeRestored((id) => navigate(`/analyses/${id}`));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const label = repoLabel(entry.repositoryUrl);
  const copy = analyse.error ? describeError(analyse.error) : null;
  return (
    <>
      <div className="grid justify-items-end gap-2xs">
        <div className="flex items-center justify-end gap-2xs">
          <Button
            size={size}
            variant="primary"
            loading={analyse.isPending}
            icon={<Play size={14} aria-hidden="true" />}
            aria-label={`Analyse ${label}`}
            onClick={() => analyse.mutate(entry.analysisId)}
          >
            Analyse
          </Button>
          <Button size={size} variant="quiet" icon={<Trash2 size={14} aria-hidden="true" />} aria-label={`Delete ${label}`} onClick={() => setConfirmDelete(true)}>
            Delete
          </Button>
        </div>
        {copy ? (
          <p role="alert" className="max-w-[28ch] text-right text-xs text-error">
            {copy.title}. {copy.message}
          </p>
        ) : null}
      </div>
      <DeleteDialog
        analysis={{ ...entry, status: 'NOT_ANALYZED' }}
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onDeleted={onDeleted}
      />
    </>
  );
}
