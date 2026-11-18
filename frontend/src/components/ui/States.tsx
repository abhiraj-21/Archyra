import type { ReactNode } from 'react';
import { RotateCw } from 'lucide-react';
import { describeError } from '@/lib/errorCopy';
import { Button } from './Button';
import { Countdown } from './Countdown';

/** What is empty, why it matters, one action. */
export function EmptyState({ title, children, action, icon }: { title: string; children?: ReactNode; action?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="grid justify-items-start gap-xs py-xl">
      {icon ? <div className="text-muted">{icon}</div> : null}
      <h3 className="text-lg">{title}</h3>
      {children ? <div className="max-w-[52ch] text-muted">{children}</div> : null}
      {action ? <div className="mt-xs">{action}</div> : null}
    </div>
  );
}

/** What broke and what to do, with a retry when the failure is transient. */
export function ErrorState({ error, onRetry, compact = false }: { error: unknown; onRetry?: () => void; compact?: boolean }) {
  const copy = describeError(error);
  return (
    <div role="alert" className={`grid justify-items-start gap-xs ${compact ? 'py-md' : 'py-xl'}`}>
      <h3 className="text-lg">{copy.title}</h3>
      <p className="max-w-[52ch] text-muted">
        {copy.message}
        {copy.retryAfter ? (
          <>
            {' '}
            <Countdown seconds={copy.retryAfter} />
          </>
        ) : null}
      </p>
      {onRetry && copy.retryable ? (
        <Button size="sm" icon={<RotateCw size={14} aria-hidden="true" />} onClick={onRetry} className="mt-xs">
          Try again
        </Button>
      ) : null}
    </div>
  );
}
