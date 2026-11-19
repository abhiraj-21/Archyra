import { Button } from '@/components/ui/Button';
import { useUpgrade } from '@/hooks/upgradeContext';

/** Replaces the composer when the free questions are used up. No further sends are possible. */
export function LimitPanel() {
  const { openUpgrade } = useUpgrade();
  return (
    <section className="well grid justify-items-start gap-xs p-md" aria-labelledby="ai-limit-title">
      <h3 id="ai-limit-title" className="text-lg">You&rsquo;ve used all your free questions</h3>
      <p className="max-w-[52ch] text-muted">The free plan includes a fixed number of Ask AI questions for the life of the account. Your conversations stay readable.</p>
      <Button variant="primary" onClick={() => openUpgrade('More Ask AI questions')}>See Premium</Button>
    </section>
  );
}
