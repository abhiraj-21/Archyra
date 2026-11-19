/**
 * When the weekly Ask AI allowance starts again, in words. One pure helper: `resetsAt` is the server's ISO-8601 instant
 * (next Monday 00:00 in its reset zone) and everything here is computed from it in the browser, in the reader's own time zone.
 *
 * - less than 24 hours away: the local clock time, 24-hour "hh:mm" ("till 05:30");
 * - otherwise whole days, rounded up ("3 days", "1 day"): exactly 24 hours is "1 day", a minute more is "2 days";
 * - already past (or now): treated as now, and `due` says the usage should be fetched again.
 *
 * Nothing here reads the clock or the time zone by itself: both are parameters (the defaults are the browser's), so the tests
 * can pin them.
 */

const MINUTE_MS = 60_000;
export const DAY_MS = 24 * 60 * MINUTE_MS;

export interface ResetWait {
  /** What follows "wait": "till 05:30", "3 days", "1 day". */
  text: string;
  kind: 'time' | 'days';
  /** The reset moment has been reached: the server's count is stale and `/usage` should be fetched again. */
  due: boolean;
  /** Milliseconds until the reset; never negative. */
  msUntil: number;
}

/**
 * "05:30" in 24-hour time. `hourCycle: 'h23'` rather than `hour12: false`: with the latter some engines print midnight as
 * "24:05" for locales whose default is a 12-hour clock. The locale is pinned so the digits and the colon never vary.
 */
function clockTime(at: number, timeZone: string | undefined): string {
  return new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone }).format(at);
}

/** Null when `resetsAt` is missing or not a date (an older server, a garbled value): the caller shows copy without a time. */
export function resetWait(resetsAt: string | null | undefined, now: number = Date.now(), timeZone?: string): ResetWait | null {
  if (!resetsAt) return null;
  const target = Date.parse(resetsAt);
  if (Number.isNaN(target)) return null;
  const remaining = target - now;
  if (remaining <= 0) return { text: `till ${clockTime(now, timeZone)}`, kind: 'time', due: true, msUntil: 0 };
  if (remaining < DAY_MS) return { text: `till ${clockTime(target, timeZone)}`, kind: 'time', due: false, msUntil: remaining };
  const days = Math.ceil(remaining / DAY_MS);
  return { text: `${days} ${days === 1 ? 'day' : 'days'}`, kind: 'days', due: false, msUntil: remaining };
}

/** The sentence shown when the free questions are used up. `when` comes from `resetWait`. */
export function outOfCreditsMessage(when: string | null): string {
  return `Out of credits. Upgrade to Premium or wait ${when ?? 'for the weekly reset'}.`;
}

/** Milliseconds until the reset, never negative; null when `resetsAt` is missing or not a date. */
export function msUntilReset(resetsAt: string | null | undefined, now: number = Date.now()): number | null {
  if (!resetsAt) return null;
  const target = Date.parse(resetsAt);
  return Number.isNaN(target) ? null : Math.max(0, target - now);
}
