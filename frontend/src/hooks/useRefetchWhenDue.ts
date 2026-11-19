import { useEffect, useRef } from 'react';

/** A timer cannot be set further out than this (2^31 - 1 ms, about 24.8 days); a week is far below it, the clamp is a guard. */
const MAX_TIMER_MS = 2_147_483_647;
/** Waited after the reset moment so the server's clock has certainly passed it too. */
const SLACK_MS = 1_000;
/** If the server still answers with a reset that is behind us (clock skew), look again a few times, then give up until focus. */
const SKEW_RETRY_MS = [5_000, 15_000, 45_000];

/**
 * Runs `refetch` once when `dueAt` (an ISO instant) is reached, and again when the tab becomes visible or the window regains
 * focus after it. No polling: one timer per reset moment, so the count and the composer unlock by themselves after the reset
 * without a reload. When the server still reports a reset that has passed (its clock is behind ours) the new `dataUpdatedAt`
 * re-arms a short, bounded retry instead of looping.
 *
 * `dataUpdatedAt` is only a trigger to re-arm after a refetch; it does not matter what it is.
 */
export function useRefetchWhenDue(dueAt: string | undefined, dataUpdatedAt: number, refetch: () => unknown): void {
  // What has been done for the reset moment being watched; survives the re-runs a refetch causes, so a lagging server clock cannot loop.
  const progress = useRef({ due: Number.NaN, retries: 0, askedPast: false });

  useEffect(() => {
    if (!dueAt) return;
    const due = Date.parse(dueAt);
    if (Number.isNaN(due)) return;
    if (progress.current.due !== due) progress.current = { due, retries: 0, askedPast: false };

    let timer: ReturnType<typeof setTimeout> | undefined;
    const fetchAgain = () => void refetch();

    const arm = () => {
      const wait = due - Date.now();
      if (wait > 0) {
        timer = setTimeout(() => (Date.now() >= due ? fetchAgain() : arm()), Math.min(wait + SLACK_MS, MAX_TIMER_MS));
        return;
      }
      // Already past: the data was fetched before the reset, or the server's clock lags ours. Ask now, then back off a few times.
      const state = progress.current;
      if (!state.askedPast) {
        state.askedPast = true;
        fetchAgain();
        return;
      }
      const retry = SKEW_RETRY_MS[state.retries++];
      if (retry !== undefined) timer = setTimeout(fetchAgain, retry);
    };

    const onWake = () => {
      if (document.visibilityState !== 'hidden' && Date.now() >= due) fetchAgain();
    };
    window.addEventListener('focus', onWake);
    document.addEventListener('visibilitychange', onWake);
    arm();
    return () => {
      if (timer) clearTimeout(timer);
      window.removeEventListener('focus', onWake);
      document.removeEventListener('visibilitychange', onWake);
    };
  }, [dueAt, dataUpdatedAt, refetch]);
}
