import type { AiIndexResponse } from '@/api/types';

/** The chat itself arrives with the next change; until then a READY index shows its state only. */
export function ChatWorkspace({ index }: { analysisId: string; repositoryUrl: string; index: AiIndexResponse }) {
  return (
    <div className="mx-auto grid w-full max-w-[40rem] gap-sm px-md py-xl">
      <h2 className="text-xl">Ask AI is ready</h2>
      {index.reason ? <p className="text-sm text-muted">{index.reason}</p> : null}
    </div>
  );
}
