import { CircleAlert, CircleCheck, CircleDashed, Clock, Hourglass, Lock, Sparkles } from 'lucide-react';
import type { AnalysisStatus, Plan } from '@/api/types';
import { statusLabel } from '@/lib/status';

/** Status as an ink-outline chip with its own icon shape, so colour is never the only signal. */
export function StatusChip({ status, progress }: { status: AnalysisStatus; progress?: number }) {
  if (status === 'COMPLETED')
    return (
      <span className="chip" data-tone="success">
        <CircleCheck size={13} aria-hidden="true" />
        Completed
      </span>
    );
  if (status === 'FAILED')
    return (
      <span className="chip" data-tone="error">
        <CircleAlert size={13} aria-hidden="true" />
        Failed
      </span>
    );
  if (status === 'NOT_ANALYZED')
    return (
      <span className="chip" data-tone="quiet">
        <CircleDashed size={13} aria-hidden="true" />
        Not analysed
      </span>
    );
  const Icon = status === 'QUEUED' ? Clock : Hourglass;
  return (
    <span className="chip" data-tone="ink">
      <Icon size={13} aria-hidden="true" />
      {statusLabel(status)}
      {progress !== undefined && status !== 'QUEUED' ? <span className="num text-muted">{progress}%</span> : null}
    </span>
  );
}

export function PlanBadge({ plan }: { plan: Plan }) {
  return plan === 'PREMIUM' ? (
    <span className="chip" data-tone="ink">
      <Sparkles size={12} aria-hidden="true" />
      Premium
    </span>
  ) : (
    <span className="chip">Free</span>
  );
}

/** The backend supports this, the plan does not. Clicking the control opens the upgrade dialog. */
export function ProBadge() {
  return (
    <span className="chip" data-tone="ink" aria-label="Premium feature">
      <Lock size={11} aria-hidden="true" />
      Pro
    </span>
  );
}

/** The backend does not have this yet, for any plan. */
export function ComingSoonBadge() {
  return (
    <span className="chip" data-tone="quiet">
      Coming soon
    </span>
  );
}
