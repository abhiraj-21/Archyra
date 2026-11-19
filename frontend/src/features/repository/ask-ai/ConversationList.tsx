import { useRef, useState } from 'react';
import { MessagesSquare, Plus, Trash2 } from 'lucide-react';
import type { ConversationSummary } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Notice } from '@/components/ui/Notice';
import { describeError } from '@/lib/errorCopy';
import { formatNumber } from '@/lib/format';

interface ListProps {
  conversations: ConversationSummary[];
  selectedId: string | null;
  creating: boolean;
  createError: unknown;
  deleting: boolean;
  deleteError: unknown;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (id: string) => Promise<void>;
}

/** The conversations of this analysis, most recent activity first. Delete asks first and then moves focus somewhere sensible. */
export function ConversationList({ conversations, selectedId, creating, createError, deleting, deleteError, onSelect, onNew, onDelete }: ListProps) {
  const [toDelete, setToDelete] = useState<ConversationSummary | null>(null);
  const newButton = useRef<HTMLButtonElement>(null);
  const createCopy = createError ? describeError(createError) : null;
  const deleteCopy = deleteError ? describeError(deleteError) : null;

  return (
    <nav aria-label="Conversations" className="grid content-start gap-sm">
      <Button ref={newButton} variant="secondary" loading={creating} icon={<Plus size={16} aria-hidden="true" />} onClick={onNew}>
        New conversation
      </Button>
      {createCopy ? <Notice tone="error" title={createCopy.title}>{createCopy.message}</Notice> : null}
      {conversations.length === 0 ? (
        <p className="text-sm text-muted">No conversations yet. Ask a question to start one.</p>
      ) : (
        <ul className="grid gap-3xs">
          {conversations.map((conversation) => {
            const selected = conversation.id === selectedId;
            return (
              <li key={conversation.id} className="flex items-center gap-3xs">
                <button
                  type="button"
                  className="menu-item min-w-0 flex-1 justify-start text-left"
                  aria-current={selected ? 'true' : undefined}
                  data-selected={selected || undefined}
                  onClick={() => onSelect(conversation.id)}
                >
                  <MessagesSquare size={14} aria-hidden="true" />
                  <span className="min-w-0 flex-1 truncate">{conversation.title}</span>
                  <span className="num text-xs text-muted">{formatNumber(conversation.messageCount)}</span>
                </button>
                <button type="button" className="btn btn-quiet btn-icon" aria-label={`Delete conversation ${conversation.title}`} onClick={() => setToDelete(conversation)}>
                  <Trash2 size={14} aria-hidden="true" />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <Dialog
        open={toDelete !== null}
        onClose={() => setToDelete(null)}
        title="Delete this conversation?"
        footer={
          <>
            <Button variant="quiet" onClick={() => setToDelete(null)}>Cancel</Button>
            <Button
              variant="danger"
              loading={deleting}
              icon={<Trash2 size={16} aria-hidden="true" />}
              onClick={() => {
                const target = toDelete;
                if (!target) return;
                void onDelete(target.id).then(
                  () => {
                    setToDelete(null);
                    newButton.current?.focus();
                  },
                  () => undefined,
                );
              }}
            >
              Delete
            </Button>
          </>
        }
      >
        <div className="grid gap-sm">
          <p>&ldquo;{toDelete?.title}&rdquo; and its messages are deleted. This cannot be undone, and it does not give back any of your free questions.</p>
          {deleteCopy ? <Notice tone="error" title={deleteCopy.title}>{deleteCopy.message}</Notice> : null}
        </div>
      </Dialog>
    </nav>
  );
}
