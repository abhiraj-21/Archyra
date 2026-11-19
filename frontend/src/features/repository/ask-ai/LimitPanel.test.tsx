import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UpgradeContext } from '@/hooks/upgradeContext';
import { LimitPanel } from './LimitPanel';

const NOW = Date.parse('2026-11-22T10:00:00Z');

function show(resetsAt: string | undefined, openUpgrade = vi.fn()) {
  render(
    <UpgradeContext.Provider value={{ openUpgrade }}>
      <LimitPanel resetsAt={resetsAt} now={NOW} />
    </UpgradeContext.Provider>,
  );
  return openUpgrade;
}
const message = () => screen.getByTestId('limit-message').textContent;

describe('LimitPanel', () => {
  afterEach(cleanup);

  it('says "wait till hh:mm" when the reset is under 24 hours away', () => {
    show('2026-11-22T18:30:00Z');
    expect(message()).toMatch(/^Out of credits\. Upgrade to Premium or wait till \d\d:\d\d\.$/);
  });

  it('says whole days, rounded up, from 24 hours on', () => {
    show('2026-11-25T10:00:00Z');
    expect(message()).toBe('Out of credits. Upgrade to Premium or wait 3 days.');
  });

  it('says "1 day" for exactly 24 hours', () => {
    show('2026-11-23T10:00:00Z');
    expect(message()).toBe('Out of credits. Upgrade to Premium or wait 1 day.');
  });

  it('treats a reset that has passed as now', () => {
    show('2026-11-22T09:00:00Z');
    expect(message()).toMatch(/^Out of credits\. Upgrade to Premium or wait till \d\d:\d\d\.$/);
  });

  it('still reads when the server sent no reset time', () => {
    show(undefined);
    expect(message()).toBe('Out of credits. Upgrade to Premium or wait for the weekly reset.');
  });

  it('keeps the upgrade action: "Upgrade to Premium" opens the upgrade dialog for Ask AI', () => {
    const openUpgrade = show('2026-11-25T10:00:00Z');

    fireEvent.click(screen.getByRole('button', { name: 'Upgrade to Premium' }));

    expect(openUpgrade).toHaveBeenCalledExactlyOnceWith('More Ask AI questions');
  });

  it('says the conversations stay readable and carries no old lifetime wording', () => {
    show('2026-11-25T10:00:00Z');
    expect(screen.getByText('Your conversations stay readable.')).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/for the life of the account|used all your free questions/i);
  });
});
