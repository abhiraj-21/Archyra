import { useAiUsage } from '@/hooks/useAccount';
import { useUpgrade } from '@/hooks/upgradeContext';
import { formatNumber } from '@/lib/format';
import { resetWait } from '@/lib/resetTime';
import { Skeleton } from '@/components/ui/Skeleton';

/**
 * The Ask AI row of the usage view: "3 of 15" from the AI_QUERY entry of GET /usage, labelled "per week, resets Monday". The
 * numbers are the server's (never a literal here); PREMIUM shows "Unlimited" with the true count. When the week's questions are
 * used up it says when they are back, like the limit panel in the Ask AI tab.
 */
export function AiUsageMeter() {
  const { entry, isPending, isError } = useAiUsage();
  const { openUpgrade } = useUpgrade();

  if (isPending) return <Skeleton className="h-10 w-full" />;
  if (isError || !entry) return <p className="text-xs text-muted">Usage is not available right now.</p>;

  if (entry.limit === null) {
    return (
      <div className="grid gap-2xs text-sm" data-testid="ai-usage">
        <p className="font-medium text-ink">Unlimited Ask AI questions</p>
        <p className="text-xs text-muted">
          <span className="num">{formatNumber(entry.used)}</span> asked this week on Premium
        </p>
      </div>
    );
  }

  const ratio = entry.limit > 0 ? Math.min(1, entry.used / entry.limit) : 1;
  const tone = entry.limitReached ? 'full' : entry.remaining === 1 ? 'warn' : undefined;
  const label = `${entry.used} of ${entry.limit} free Ask AI questions used this week`;
  const when = resetWait(entry.resetsAt)?.text ?? 'for the weekly reset';

  return (
    <div className="grid gap-xs" data-testid="ai-usage">
      <div className="flex items-baseline justify-between gap-sm text-sm">
        <span className="font-medium text-ink">Ask AI questions</span>
        <span className="text-muted">
          <span className="num text-ink-2">{formatNumber(entry.used)}</span> of <span className="num">{formatNumber(entry.limit)}</span>
          <span className="text-xs"> per week, resets Monday</span>
        </span>
      </div>
      <div className="meter" data-tone={tone} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={entry.limit} aria-valuenow={entry.used} aria-valuetext={label}>
        <span style={{ transform: `scaleX(${ratio})` }} />
      </div>
      {entry.limitReached ? (
        <p className="text-xs text-error">
          Out of credits.{' '}
          <button type="button" className="link" onClick={() => openUpgrade('More Ask AI questions')}>
            Upgrade to Premium
          </button>{' '}
          or wait {when}.
        </p>
      ) : null}
    </div>
  );
}
