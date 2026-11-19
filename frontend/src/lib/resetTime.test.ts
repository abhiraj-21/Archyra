import { describe, expect, it } from 'vitest';
import { DAY_MS, msUntilReset, outOfCreditsMessage, resetWait } from './resetTime';

const KOLKATA = 'Asia/Kolkata';
const NEW_YORK = 'America/New_York';
const at = (iso: string) => Date.parse(iso);

describe('resetWait: under 24 hours is a clock time', () => {
  it('says the local hh:mm of the reset, 24-hour', () => {
    const wait = resetWait('2026-11-22T18:30:00Z', at('2026-11-22T10:00:00Z'), 'UTC');

    expect(wait).toEqual({ text: 'till 18:30', kind: 'time', due: false, msUntil: at('2026-11-22T18:30:00Z') - at('2026-11-22T10:00:00Z') });
  });

  it('writes a morning time with a leading zero and no AM/PM', () => {
    expect(resetWait('2026-11-23T05:30:00Z', at('2026-11-23T01:00:00Z'), 'UTC')?.text).toBe('till 05:30');
  });

  it('writes midnight as 00:xx, never 24:xx', () => {
    expect(resetWait('2026-11-23T00:05:00Z', at('2026-11-22T20:00:00Z'), 'UTC')?.text).toBe('till 00:05');
    expect(resetWait('2026-11-23T00:00:00Z', at('2026-11-22T20:00:00Z'), 'UTC')?.text).toBe('till 00:00');
  });

  it('is 23:59:59.999 away: still a clock time', () => {
    const now = at('2026-11-22T10:00:00Z');
    expect(resetWait(new Date(now + DAY_MS - 1).toISOString(), now, 'UTC')).toMatchObject({ kind: 'time', due: false });
  });

  it('is under a minute away: still the clock time of the reset', () => {
    const now = at('2026-11-22T18:29:30Z');
    expect(resetWait('2026-11-22T18:30:00Z', now, 'UTC')).toEqual({ text: 'till 18:30', kind: 'time', due: false, msUntil: 30_000 });
  });

  it('is one millisecond away', () => {
    const now = at('2026-11-22T18:29:59.999Z');
    expect(resetWait('2026-11-22T18:30:00Z', now, 'UTC')).toMatchObject({ kind: 'time', due: false, msUntil: 1 });
  });

  it('can cross midnight: the time is still just the clock time', () => {
    // now 23:30 on Sunday, the reset is 05:30 on Monday: 6 hours away
    expect(resetWait('2026-11-23T05:30:00Z', at('2026-11-22T23:30:00Z'), 'UTC')?.text).toBe('till 05:30');
  });
});

describe('resetWait: 24 hours or more is whole days, rounded up', () => {
  const now = at('2026-11-20T10:00:00Z');

  it('exactly 24 hours is 1 day (not a clock time)', () => {
    expect(resetWait(new Date(now + DAY_MS).toISOString(), now, 'UTC')).toEqual({ text: '1 day', kind: 'days', due: false, msUntil: DAY_MS });
  });

  it('24 hours and a millisecond is 2 days', () => {
    expect(resetWait(new Date(now + DAY_MS + 1).toISOString(), now, 'UTC')?.text).toBe('2 days');
  });

  it('rounds up: 2 days 1 hour is 3 days', () => {
    expect(resetWait(new Date(now + 2 * DAY_MS + 3_600_000).toISOString(), now, 'UTC')?.text).toBe('3 days');
  });

  it('exactly 3 days is 3 days; almost a full week is 7 days', () => {
    expect(resetWait(new Date(now + 3 * DAY_MS).toISOString(), now, 'UTC')?.text).toBe('3 days');
    expect(resetWait(new Date(now + 7 * DAY_MS - 1).toISOString(), now, 'UTC')?.text).toBe('7 days');
  });
});

describe('resetWait: the reader\'s time zone', () => {
  const now = at('2026-11-22T12:00:00Z');
  const reset = '2026-11-22T18:30:00Z'; // Monday 00:00 in Kolkata

  it('shows the same moment in each browser time zone', () => {
    expect(resetWait(reset, now, KOLKATA)?.text).toBe('till 00:00');
    expect(resetWait(reset, now, NEW_YORK)?.text).toBe('till 13:30');
    expect(resetWait(reset, now, 'UTC')?.text).toBe('till 18:30');
    expect(resetWait(reset, now, 'Asia/Kathmandu')?.text).toBe('till 00:15'); // a +05:45 zone
  });

  it('follows a daylight-saving change in the reader\'s zone', () => {
    const winter = resetWait('2026-11-23T04:00:00Z', at('2026-11-23T00:00:00Z'), NEW_YORK); // EST, UTC-5
    const summer = resetWait('2026-07-06T04:00:00Z', at('2026-07-06T00:00:00Z'), NEW_YORK); // EDT, UTC-4
    expect(winter?.text).toBe('till 23:00');
    expect(summer?.text).toBe('till 00:00');
  });

  it('counts days from the instants, so the zone does not change them', () => {
    const start = at('2026-11-20T10:00:00Z');
    const end = '2026-11-23T10:00:00Z';
    expect(resetWait(end, start, KOLKATA)?.text).toBe('3 days');
    expect(resetWait(end, start, NEW_YORK)?.text).toBe('3 days');
  });
});

describe('resetWait: past, missing and garbled values', () => {
  it('treats a past reset as now and says it is due', () => {
    const now = at('2026-11-23T09:15:00Z');
    expect(resetWait('2026-11-22T18:30:00Z', now, 'UTC')).toEqual({ text: 'till 09:15', kind: 'time', due: true, msUntil: 0 });
  });

  it('treats exactly now as due', () => {
    const now = at('2026-11-22T18:30:00Z');
    expect(resetWait('2026-11-22T18:30:00Z', now, 'UTC')).toMatchObject({ due: true, msUntil: 0 });
  });

  it('gives null for a missing or garbled value', () => {
    expect(resetWait(undefined)).toBeNull();
    expect(resetWait(null)).toBeNull();
    expect(resetWait('')).toBeNull();
    expect(resetWait('next monday')).toBeNull();
  });
});

describe('outOfCreditsMessage', () => {
  it('reads naturally for a time and for days', () => {
    expect(outOfCreditsMessage('till 05:30')).toBe('Out of credits. Upgrade to Premium or wait till 05:30.');
    expect(outOfCreditsMessage('3 days')).toBe('Out of credits. Upgrade to Premium or wait 3 days.');
    expect(outOfCreditsMessage('1 day')).toBe('Out of credits. Upgrade to Premium or wait 1 day.');
  });

  it('still reads when the time is unknown', () => {
    expect(outOfCreditsMessage(null)).toBe('Out of credits. Upgrade to Premium or wait for the weekly reset.');
  });

  it('composes with resetWait', () => {
    const now = at('2026-11-22T10:00:00Z');
    expect(outOfCreditsMessage(resetWait('2026-11-22T14:30:00Z', now, 'UTC')?.text ?? null)).toBe('Out of credits. Upgrade to Premium or wait till 14:30.');
    expect(outOfCreditsMessage(resetWait('2026-11-25T10:00:00Z', now, 'UTC')?.text ?? null)).toBe('Out of credits. Upgrade to Premium or wait 3 days.');
    expect(outOfCreditsMessage(resetWait('2026-11-23T10:00:00Z', now, 'UTC')?.text ?? null)).toBe('Out of credits. Upgrade to Premium or wait 1 day.');
  });
});

describe('msUntilReset', () => {
  it('is the distance, never negative', () => {
    expect(msUntilReset('2026-11-22T18:30:00Z', at('2026-11-22T18:00:00Z'))).toBe(30 * 60_000);
    expect(msUntilReset('2026-11-22T18:30:00Z', at('2026-11-23T00:00:00Z'))).toBe(0);
    expect(msUntilReset('garbage')).toBeNull();
    expect(msUntilReset(undefined)).toBeNull();
  });
});
