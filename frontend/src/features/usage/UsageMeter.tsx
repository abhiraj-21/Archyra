import { useAnalysisUsage } from '@/hooks/useAccount';
import { useUpgrade } from '@/hooks/upgradeContext';
import { useBillingStatus } from '@/hooks/useBilling';
import { shortDateTime } from '@/lib/billing';
import { formatNumber } from '@/lib/format';
import { Skeleton } from '@/components/ui/Skeleton';

/**
 * "3 of 5 analyses" from GET /usage. PREMIUM shows "Unlimited" with the true count. Never computed client-side:
 * every create, delete, re-run and FAILED poll refetches /usage.
 */
export function UsageMeter({ variant = 'full' }: { variant?: 'full' | 'compact' }) {
  const { entry, isPending, isError } = useAnalysisUsage();
  const { openUpgrade } = useUpgrade();
  const billing = useBillingStatus();

  if (isPending) return <Skeleton className="h-10 w-full" />;
  if (isError || !entry) return <p className="text-xs text-muted">Usage is not available right now.</p>;

  if (entry.limit === null) {
    return (
      <div className="grid gap-2xs text-sm">
        {billing.data?.effectivePlan === 'PREMIUM' ? (
          <p className="text-xs text-ink-2" data-testid="premium-until">
            {billing.data.planExpiresAt ? `Premium until ${shortDateTime(billing.data.planExpiresAt)}` : 'Premium, no end date'}
          </p>
        ) : null}
        <p className="font-medium text-ink">Unlimited analyses</p>
        <p className="text-xs text-muted">
          <span className="num">{formatNumber(entry.used)}</span> run so far on Premium
        </p>
      </div>
    );
  }

  const ratio = entry.limit > 0 ? Math.min(1, entry.used / entry.limit) : 1;
  const tone = entry.limitReached ? 'full' : entry.remaining === 1 ? 'warn' : undefined;
  const label = `${entry.used} of ${entry.limit} free analyses used`;

  return (
    <div className="grid gap-xs">
      <div className="flex items-baseline justify-between gap-sm text-sm">
        <span className="font-medium text-ink">Free analyses</span>
        <span className="text-muted">
          <span className="num text-ink-2">{formatNumber(entry.used)}</span> of <span className="num">{formatNumber(entry.limit)}</span>
        </span>
      </div>
      <div className="meter" data-tone={tone} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={entry.limit} aria-valuenow={entry.used} aria-valuetext={label}>
        <span style={{ transform: `scaleX(${ratio})` }} />
      </div>
      {variant === 'full' ? (
        entry.limitReached ? (
          <p className="text-xs text-error">
            Limit reached. Deleting an analysis returns its credit.{' '}
            <button type="button" className="link" onClick={() => openUpgrade('More analyses')}>
              Upgrade
            </button>
          </p>
        ) : entry.remaining === 1 ? (
          <p className="text-xs text-warn">One free analysis left.</p>
        ) : (
          <p className="text-xs text-muted">A lifetime allowance; deleting an analysis returns its credit.</p>
        )
      ) : null}
    </div>
  );
}
