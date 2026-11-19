import { cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useRefetchWhenDue } from './useRefetchWhenDue';

const START = Date.parse('2026-11-22T10:00:00Z');
const RESET = '2026-11-22T18:30:00Z';
const UNTIL_RESET = Date.parse(RESET) - START;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(START);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('useRefetchWhenDue', () => {
  it('fetches once when the reset moment has passed, and not before', () => {
    const refetch = vi.fn();
    renderHook(() => useRefetchWhenDue(RESET, 1, refetch));

    vi.advanceTimersByTime(UNTIL_RESET - 1);
    expect(refetch).not.toHaveBeenCalled();

    vi.advanceTimersByTime(2_000);
    expect(refetch).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(24 * 3_600_000); // no polling afterwards
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('does nothing without a reset moment or with one that is not a date', () => {
    const refetch = vi.fn();
    renderHook(() => useRefetchWhenDue(undefined, 1, refetch));
    renderHook(() => useRefetchWhenDue('garbage', 1, refetch));

    vi.advanceTimersByTime(30 * 24 * 3_600_000);
    expect(refetch).not.toHaveBeenCalled();
  });

  it('fetches straight away when the stored reset is already behind us (a page left open over the reset)', () => {
    const refetch = vi.fn();
    vi.setSystemTime(Date.parse(RESET) + 60_000);

    renderHook(() => useRefetchWhenDue(RESET, 1, refetch));

    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('fetches when the window regains focus after the reset, and not before it', () => {
    const refetch = vi.fn();
    renderHook(() => useRefetchWhenDue(RESET, 1, refetch));

    window.dispatchEvent(new Event('focus'));
    expect(refetch).not.toHaveBeenCalled();

    vi.setSystemTime(Date.parse(RESET) + 5_000); // the laptop slept through the timer
    window.dispatchEvent(new Event('focus'));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('re-arms for the next reset when new data arrives, and stops watching the old one', () => {
    const refetch = vi.fn();
    const next = '2026-11-29T18:30:00Z';
    const { rerender } = renderHook(({ due, at }: { due: string; at: number }) => useRefetchWhenDue(due, at, refetch), {
      initialProps: { due: RESET, at: 1 },
    });
    vi.advanceTimersByTime(UNTIL_RESET + 2_000);
    expect(refetch).toHaveBeenCalledTimes(1);

    rerender({ due: next, at: 2 }); // the refetch brought next week's reset
    vi.advanceTimersByTime(6 * 24 * 3_600_000);
    expect(refetch).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(2 * 24 * 3_600_000);
    expect(refetch).toHaveBeenCalledTimes(2);
  });

  it('backs off a few times, then gives up, when the server still reports a reset that has passed', () => {
    const refetch = vi.fn();
    vi.setSystemTime(Date.parse(RESET) + 1_000);
    const { rerender } = renderHook(({ at }: { at: number }) => useRefetchWhenDue(RESET, at, refetch), { initialProps: { at: 1 } });
    expect(refetch).toHaveBeenCalledTimes(1);

    for (let round = 2; round <= 8; round++) {
      rerender({ at: round }); // each answer carries the same, past, reset
      vi.advanceTimersByTime(60_000);
    }

    expect(refetch.mock.calls.length).toBeLessThanOrEqual(5); // one now, then at most three delayed retries: never a loop
    const settled = refetch.mock.calls.length;
    vi.advanceTimersByTime(10 * 60_000);
    expect(refetch.mock.calls.length).toBe(settled);
  });

  it('clears its timer and listeners on unmount', () => {
    const refetch = vi.fn();
    const { unmount } = renderHook(() => useRefetchWhenDue(RESET, 1, refetch));

    unmount();
    vi.advanceTimersByTime(2 * UNTIL_RESET);
    vi.setSystemTime(Date.parse(RESET) + 5_000);
    window.dispatchEvent(new Event('focus'));

    expect(refetch).not.toHaveBeenCalled();
  });
});
