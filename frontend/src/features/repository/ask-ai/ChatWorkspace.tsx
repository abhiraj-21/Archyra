import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { PanelLeft } from 'lucide-react';
import { ApiError, hasCode, isApiError } from '@/api/errors';
import type { AiIndexResponse } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useCountdown } from '@/components/ui/useCountdown';
import { useAiUsage } from '@/hooks/useAccount';
import { useGraph } from '@/hooks/useWorkspace';
import { useAskQuestion, useConversationMessages, useConversations, useCreateConversation, useDeleteConversation, useRefreshAiIndex } from '@/hooks/useAskAi';
import { questionsLabel, retrySeconds } from '@/lib/askAi';
import { buildExamples, COUNTING_NOTE } from '@/lib/askGuide';
import { describeError } from '@/lib/errorCopy';
import { ChatThread } from './ChatThread';
import { Composer, DATA_USE_NOTE } from './Composer';
import { ConversationList } from './ConversationList';
import { LimitPanel } from './LimitPanel';
import { GuideToggle, QuestionGuide } from './QuestionGuide';

interface Failure {
  id: number;
  message: string;
  error: unknown;
}

/** The chat for a READY index: conversations on the left, the thread and the composer on the right. */
export function ChatWorkspace({ analysisId, index }: { analysisId: string; repositoryUrl: string; index: AiIndexResponse }) {
  const [params, setParams] = useSearchParams();
  const selectedId = params.get('c');
  const selectedRef = useRef(selectedId);
  selectedRef.current = selectedId;

  const conversations = useConversations(analysisId, true);
  const messages = useConversationMessages(selectedId);
  const create = useCreateConversation(analysisId);
  const remove = useDeleteConversation(analysisId);
  const ask = useAskQuestion(analysisId);
  const usage = useAiUsage();
  const refreshIndex = useRefreshAiIndex(analysisId);
  // The examples use this repository's own names, from the graph the Architecture tab already caches (placeholders until it is there).
  const graph = useGraph(analysisId, true);
  const examples = useMemo(() => buildExamples(graph.data), [graph.data]);
  const [guideOpen, setGuideOpen] = useState(false);
  const guideId = useId();

  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState<{ conversation: string | null; message: string } | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  // The server refused a question for the limit: `resetsAt` is from its body. Kept until `/usage`, fetched after that moment, says the
  // questions are back (a limit that the entry already reports needs no state).
  const [limitHit, setLimitHit] = useState<{ resetsAt: string | undefined } | null>(null);
  const [rateLimit, setRateLimit] = useState<{ id: number; seconds: number } | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [missing, setMissing] = useState(false);
  const composer = useRef<HTMLTextAreaElement>(null);
  const attempt = useRef(0);

  const select = (id: string | null, replace = false) => {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (id) next.set('c', id);
        else next.delete('c');
        return next;
      },
      { replace },
    );
    setFailure(null);
    setMissing(false);
    setListOpen(false);
  };

  // A deep link or reload with an unknown or deleted conversation falls back to the empty state.
  useEffect(() => {
    if (selectedId && hasCode(messages.error, 'CONVERSATION_NOT_FOUND')) {
      setMissing(true);
      setParams((current) => {
        const next = new URLSearchParams(current);
        next.delete('c');
        return next;
      }, { replace: true });
    }
  }, [selectedId, messages.error, setParams]);

  const send = async (text: string) => {
    const conversation = selectedId;
    setFailure(null);
    setMissing(false);
    setPending({ conversation, message: text });
    setDraft('');
    try {
      const answer = await ask.mutateAsync(conversation ? { message: text, conversationId: conversation } : { message: text });
      setPending(null);
      // Follow the conversation the server used, unless the reader moved to another one meanwhile.
      if (selectedRef.current === conversation && answer.conversationId !== conversation) select(answer.conversationId);
      composer.current?.focus();
    } catch (error) {
      setPending(null);
      setDraft((current) => (current === '' ? text : current)); // the question is never lost
      attempt.current += 1;
      handleFailure(error, text, attempt.current);
      composer.current?.focus();
    }
  };

  const handleFailure = (error: unknown, text: string, id: number) => {
    if (hasCode(error, 'USAGE_LIMIT_EXCEEDED')) {
      setLimitHit({ resetsAt: error.resetsAt });
      void usage.refetch(); // the count and the reset moment are the server's
      return;
    }
    if (hasCode(error, 'AI_NOT_READY')) void refreshIndex();
    if (hasCode(error, 'QUESTION_NOT_ANSWERABLE')) setGuideOpen(true); // the text stays in the box; the guide says what to ask instead
    if (hasCode(error, 'RATE_LIMITED')) setRateLimit({ id, seconds: retrySeconds(isApiError(error) ? error.retryAfter : undefined) });
    setFailure({ id, message: text, error });
  };

  const items = conversations.data?.items ?? [];
  const sendingHere = pending !== null && pending.conversation === selectedId;
  const entry = usage.entry;
  const limitReached = limitHit !== null || entry?.limitReached === true;
  const limitResetsAt = entry?.limitReached ? entry.resetsAt : (limitHit?.resetsAt ?? entry?.resetsAt);
  // The week turned: a `/usage` read at or after the reset that no longer reports the limit ends the refusal, so the composer is back.
  const entryLimitReached = entry?.limitReached;
  const usageReadAt = usage.dataUpdatedAt;
  useEffect(() => {
    if (limitHit === null) return;
    const reset = Date.parse(limitHit.resetsAt ?? '');
    if (!Number.isNaN(reset) && entryLimitReached === false && usageReadAt >= reset) setLimitHit(null);
  }, [limitHit, entryLimitReached, usageReadAt]);
  const disabledReason = sendingHere ? 'Waiting for the answer…' : rateLimit ? 'Too many questions. Wait for the countdown to end.' : null;
  const usageLabel = entry ? questionsLabel(entry) : null;
  const onExample = (question: string) => {
    setDraft(question);
    composer.current?.focus();
  };
  const conversationIsEmpty = selectedId === null || (messages.data !== undefined && messages.data.items.length === 0 && pending === null);

  const failureNotice = failure ? (
    <FailureNotice
      key={failure.id}
      failure={failure}
      onRetry={() => void send(failure.message)}
      onNewConversation={() => {
        select(null);
        composer.current?.focus();
      }}
      onRateLimitDone={() => setRateLimit(null)}
      rateSeconds={rateLimit?.id === failure.id ? rateLimit.seconds : undefined}
    />
  ) : missing ? (
    <Notice tone="info" title="That conversation no longer exists">Pick another one, or start a new conversation.</Notice>
  ) : null;

  return (
    <div className="mx-auto grid w-full max-w-[72rem] gap-md px-md py-md sm:px-lg lg:h-[calc(100dvh-11rem)] lg:min-h-[32rem] lg:grid-cols-[17rem_minmax(0,1fr)]">
      <aside className="grid content-start gap-sm lg:overflow-y-auto">
        <Button className="lg:hidden" variant="quiet" icon={<PanelLeft size={16} aria-hidden="true" />} aria-expanded={listOpen} aria-controls="ai-conversations" onClick={() => setListOpen((open) => !open)}>
          Conversations
        </Button>
        <div id="ai-conversations" className={listOpen ? 'block' : 'hidden lg:block'}>
          {conversations.isPending ? (
            <div role="status" aria-label="Loading conversations" className="grid gap-xs">
              <Skeleton className="h-9" />
              <Skeleton className="h-9" />
            </div>
          ) : conversations.isError && !conversations.data ? (
            <ErrorState compact error={conversations.error} onRetry={() => void conversations.refetch()} />
          ) : (
            <ConversationList
              conversations={items}
              selectedId={selectedId}
              creating={create.isPending}
              createError={create.error}
              deleting={remove.isPending}
              deleteError={remove.error}
              onSelect={(id) => select(id)}
              onNew={() =>
                create.mutate(undefined, {
                  onSuccess: (created) => {
                    select(created.id);
                    composer.current?.focus();
                  },
                })
              }
              onDelete={async (id) => {
                await remove.mutateAsync(id);
                if (id === selectedId) select(items.find((c) => c.id !== id)?.id ?? null, true);
              }}
            />
          )}
        </div>
      </aside>

      <section className="flex min-h-[28rem] min-w-0 flex-col gap-sm lg:min-h-0" aria-label="Chat">
        {index.reason ? <p className="text-xs text-muted">{index.reason}</p> : null}
        {messages.isError && !messages.data && !hasCode(messages.error, 'CONVERSATION_NOT_FOUND') ? (
          <ErrorState compact error={messages.error} onRetry={() => void messages.refetch()} />
        ) : (
          <ChatThread
            analysisId={analysisId}
            messages={selectedId ? (messages.data?.items ?? []) : []}
            pendingMessage={sendingHere ? pending.message : null}
            status={failureNotice}
            loading={selectedId !== null && messages.isPending}
            emptyState={<QuestionGuide examples={examples} usage={usageLabel} onExample={onExample} />}
          />
        )}
        <div className="grid gap-xs border-t border-rule pt-sm">
          {guideOpen && !conversationIsEmpty ? (
            <div id={guideId} className="max-h-[40dvh] overflow-y-auto rounded-card border border-rule p-md">
              <QuestionGuide examples={examples} usage={usageLabel} onExample={onExample} />
            </div>
          ) : null}
          <div className="flex flex-wrap items-center justify-between gap-xs">
            {usageLabel ? (
              <p className="text-xs">
                <span className="chip" data-tone={usageLabel.tone === 'normal' ? undefined : usageLabel.tone} role="status">{usageLabel.text}</span>
              </p>
            ) : (
              <span />
            )}
            {conversationIsEmpty ? null : <GuideToggle open={guideOpen} controls={guideId} onToggle={() => setGuideOpen((open) => !open)} />}
          </div>
          {limitReached ? (
            <LimitPanel resetsAt={limitResetsAt} />
          ) : (
            <Composer ref={composer} value={draft} onChange={setDraft} onSend={() => void send(draft.trim())} disabledReason={disabledReason} sending={sendingHere} />
          )}
          <p className="max-w-[80ch] text-xs text-muted" data-testid="counting-note">
            {usageLabel?.allowance ? `${usageLabel.allowance} ` : null}
            {COUNTING_NOTE}
          </p>
          <p className="max-w-[80ch] text-xs text-muted">{DATA_USE_NOTE}</p>
        </div>
      </section>
    </div>
  );
}

function FailureNotice({ failure, onRetry, onNewConversation, onRateLimitDone, rateSeconds }: {
  failure: Failure;
  onRetry: () => void;
  onNewConversation: () => void;
  onRateLimitDone: () => void;
  rateSeconds: number | undefined;
}) {
  const { error } = failure;
  if (hasCode(error, 'RATE_LIMITED')) return <RateLimitNotice seconds={rateSeconds ?? retrySeconds(undefined)} onDone={onRateLimitDone} />;
  if (hasCode(error, 'AI_UNAVAILABLE') || hasCode(error, 'TIMEOUT') || hasCode(error, 'NETWORK_ERROR')) {
    const copy = describeError(hasCode(error, 'AI_UNAVAILABLE') ? error : new ApiError(503, 'AI_UNAVAILABLE', '')) // a client timeout (about 75 s) reads the same: not counted;
    return (
      <Notice tone="error" title={copy.title} action={<Button size="sm" onClick={onRetry}>Try again</Button>}>
        {copy.message}
      </Notice>
    );
  }
  if (hasCode(error, 'CONVERSATION_FULL')) {
    return (
      <Notice tone="warn" title={describeError(error).title} action={<Button size="sm" onClick={onNewConversation}>Start a new conversation</Button>}>
        {describeError(error).message} Your question is still in the box.
      </Notice>
    );
  }
  if (hasCode(error, 'QUESTION_NOT_ANSWERABLE')) {
    const copy = describeError(error);
    // No "Try again": the same question would be refused again. The text is back in the box, to be edited.
    return (
      <Notice tone="warn" title={copy.title}>
        <p>&ldquo;{failure.message}&rdquo; was not sent.</p>
        <p>{copy.message}</p>
      </Notice>
    );
  }
  const copy = describeError(error);
  return (
    <Notice tone={hasCode(error, 'CONVERSATION_BUSY') || hasCode(error, 'AI_NOT_READY') ? 'warn' : 'error'} title={copy.title}>
      {copy.message}
    </Notice>
  );
}

function RateLimitNotice({ seconds, onDone }: { seconds: number; onDone: () => void }) {
  const left = useCountdown(seconds);
  useEffect(() => {
    if (left === 0) onDone();
  }, [left, onDone]);
  return (
    <Notice tone="warn" title="Too many questions">
      <span aria-live="polite">{left > 0 ? <>You can ask again in <span className="num">{left}</span>&nbsp;s. Your question is still in the box.</> : 'You can ask again now.'}</span>
    </Notice>
  );
}
