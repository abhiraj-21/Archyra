/**
 * When the weekly Ask AI allowance starts again, in words. One pure helper: `resetsAt` is the server's ISO-8601 instant
 * (next Monday 00:00 in its reset zone) and everything here is computed from it in the browser, in the reader's own time zone.
 *
 * - less than 24 hours away: the local clock time, 24-hour "hh:mm" ("till 05:30");
 * - otherwise whole days, rounded up ("3 days", "1 day"): exactly 24 hours is "1 day", a minute more is "2 days";
 * - already past (or now): treated as now, and `due` says the usage should be fetched again.
 *
 * `timeUntil` is the general form (the Premium expiry notices use it too); `resetWait` is it for the Ask AI reset.
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

/**
 * How long until `iso`, in the words of the Ask AI reset message: under 24 hours the reader's local clock time ("till 05:30"),
 * otherwise whole days rounded up ("3 days"); a moment already past is now and `due`. The one helper behind `resetWait` and the
 * Premium expiry notices, so both say time the same way. Null when `iso` is missing or not a date.
 */
export function timeUntil(iso: string | null | undefined, now: number = Date.now(), timeZone?: string): ResetWait | null {
  if (!iso) return null;
  const target = Date.parse(iso);
  if (Number.isNaN(target)) return null;
  const remaining = target - now;
  if (remaining <= 0) return { text: `till ${clockTime(now, timeZone)}`, kind: 'time', due: true, msUntil: 0 };
  if (remaining < DAY_MS) return { text: `till ${clockTime(target, timeZone)}`, kind: 'time', due: false, msUntil: remaining };
  const days = Math.ceil(remaining / DAY_MS);
  return { text: `${days} ${days === 1 ? 'day' : 'days'}`, kind: 'days', due: false, msUntil: remaining };
}

/** Null when `resetsAt` is missing or not a date (an older server, a garbled value): the caller shows copy without a time. */
export function resetWait(resetsAt: string | null | undefined, now: number = Date.now(), timeZone?: string): ResetWait | null {
  return timeUntil(resetsAt, now, timeZone);
}

/** The local calendar day of an instant in `timeZone`, as "2026-11-27" (comparable as a string). */
function localDay(at: number, timeZone: string | undefined): string {
  return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone }).format(at);
}

/**
 * "in 3 days", "today at 05:30", "tomorrow at 05:30", or "now" for a moment already reached: `timeUntil` with the day named
 * when it is a clock time. Null for a missing or unreadable instant.
 */
export function whenPhrase(iso: string | null | undefined, now: number = Date.now(), timeZone?: string): string | null {
  const wait = timeUntil(iso, now, timeZone);
  if (!wait || !iso) return null;
  if (wait.due) return 'now';
  if (wait.kind === 'days') return `in ${wait.text}`;
  const day = localDay(Date.parse(iso), timeZone) === localDay(now, timeZone) ? 'today' : 'tomorrow';
  return `${day} at ${clockTime(Date.parse(iso), timeZone)}`;
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
