import { useId, useMemo, useRef, useState } from 'react';
import type { ChatMessage, ChatSource } from '@/api/types';
import { providerNote } from '@/lib/askAi';
import { MarkdownView } from './MarkdownView';
import { SourceChip, SourcePanel } from './SourceChip';

/** One message. A user message is plain text; an answer is rendered safely with citations, sources and a quiet footer. */
export function MessageView({ message, analysisId }: { message: ChatMessage; analysisId: string }) {
  if (message.role === 'USER') return <UserMessage text={message.content} />;
  return <AnswerView message={message} analysisId={analysisId} />;
}

export function UserMessage({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <p className="max-w-[44rem] whitespace-pre-wrap break-words rounded-card bg-paper-3 px-md py-sm text-ink" data-role="user">
        {text}
      </p>
    </div>
  );
}

function AnswerView({ message, analysisId }: { message: ChatMessage; analysisId: string }) {
  const sources = useMemo(() => message.sources ?? [], [message.sources]);
  const base = useId();
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());
  const panels = useRef(new Map<string, HTMLElement>());
  const refs = useMemo(() => new Set(sources.map((s) => s.ref)), [sources]);

  const toggle = (id: string) => setOpen((current) => {
    const next = new Set(current);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  });

  // A citation marker expands every source of that number and moves focus to the first of them.
  const cite = (ref: number) => {
    const matching = sources.filter((s) => s.ref === ref);
    if (matching.length === 0) return;
    setOpen((current) => new Set([...current, ...matching.map((s) => s.id)]));
    const first = matching[0] as ChatSource;
    window.setTimeout(() => {
      const panel = panels.current.get(first.id);
      panel?.scrollIntoView({ block: 'nearest' });
      panel?.focus();
    }, 0);
  };

  return (
    <article className="grid max-w-[48rem] gap-sm" aria-label="Answer" data-role="assistant">
      <MarkdownView markdown={message.content} citationRefs={refs} onCite={cite} />
      {sources.length === 0 ? (
        <p className="text-xs text-muted">No sources cited</p>
      ) : (
        <div className="grid gap-xs">
          <p className="text-xs text-muted">Sources</p>
          <ul className="flex flex-wrap gap-xs">
            {sources.map((source) => (
              <li key={source.id}>
                <SourceChip source={source} expanded={open.has(source.id)} panelId={`${base}-${source.id}`} onToggle={() => toggle(source.id)} />
              </li>
            ))}
          </ul>
          {sources.filter((s) => open.has(s.id)).map((source) => (
            <SourcePanel
              key={source.id}
              source={source}
              panelId={`${base}-${source.id}`}
              analysisId={analysisId}
              ref={(el) => {
                if (el) panels.current.set(source.id, el);
                else panels.current.delete(source.id);
              }}
            />
          ))}
        </div>
      )}
      <p className="text-xs text-muted">
        {message.provider ? providerNote(message.provider) : null}
        {message.cached ? <>{message.provider ? ' · ' : ''}from this conversation&rsquo;s cache, no question used</> : null}
      </p>
    </article>
  );
}
