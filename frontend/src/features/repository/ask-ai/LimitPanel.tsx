import { useUpgrade } from '@/hooks/upgradeContext';
import { resetWait } from '@/lib/resetTime';

/**
 * Replaces the composer when this week's free questions are used up: "Out of credits. Upgrade to Premium or wait till 05:30." (a
 * clock time when the reset is under 24 hours away, else "wait 3 days"), with "Upgrade to Premium" opening the upgrade dialog.
 * `resetsAt` is the server's instant; the wording is computed here in the reader's time zone. `now` is only for tests.
 * No further sends are possible while it shows.
 */
export function LimitPanel({ resetsAt, now = Date.now() }: { resetsAt?: string | undefined; now?: number }) {
  const { openUpgrade } = useUpgrade();
  const when = resetWait(resetsAt, now)?.text ?? 'for the weekly reset';
  return (
    <section className="well grid justify-items-start gap-xs p-md" aria-label="Free questions used up">
      <p className="max-w-[60ch] text-lg" data-testid="limit-message" role="status">
        Out of credits.{' '}
        <button type="button" className="link" onClick={() => openUpgrade('More Ask AI questions')}>
          Upgrade to Premium
        </button>{' '}
        or wait {when}.
      </p>
      <p className="max-w-[52ch] text-sm text-muted">Your conversations stay readable.</p>
    </section>
  );
}
