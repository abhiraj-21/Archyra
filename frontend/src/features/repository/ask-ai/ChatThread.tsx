import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import type { ChatMessage } from '@/api/types';
import { Spinner } from '@/components/ui/Spinner';
import { MessageView, UserMessage } from './MessageView';

interface ThreadProps {
  analysisId: string;
  messages: ChatMessage[];
  /** The question being answered right now: shown at once, with a thinking placeholder after it. */
  pendingMessage: string | null;
  /** An inline error (or notice) for the last turn. */
  status: ReactNode;
  loading: boolean;
  /** Shown in an empty conversation: the question guide. */
  emptyState: ReactNode;
}

/** The scrolling history. New content keeps the view at the bottom unless the reader scrolled up. */
export function ChatThread({ analysisId, messages, pendingMessage, status, loading, emptyState }: ThreadProps) {
  const scroller = useRef<HTMLDivElement>(null);
  const pinned = useRef(true);

  const onScroll = () => {
    const el = scroller.current;
    if (el) pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  };

  useEffect(() => {
    const el = scroller.current;
    if (el && pinned.current) el.scrollTop = el.scrollHeight;
  }, [messages.length, pendingMessage, status]);

  const empty = !loading && messages.length === 0 && pendingMessage === null;

  return (
    <div ref={scroller} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto py-sm" role="log" aria-label="Conversation" aria-relevant="additions">
      <div className="grid gap-lg">
        {loading ? <p className="text-sm text-muted" role="status">Loading the conversation…</p> : null}
        {empty ? emptyState : null}
        {messages.map((message) => (
          <MessageView key={message.id} message={message} analysisId={analysisId} />
        ))}
        {pendingMessage !== null ? (
          <>
            <UserMessage text={pendingMessage} />
            <p className="inline-flex items-center gap-xs text-muted" role="status" aria-live="polite">
              <Spinner size={16} /> Thinking…
            </p>
          </>
        ) : null}
        {status}
      </div>
    </div>
  );
}
