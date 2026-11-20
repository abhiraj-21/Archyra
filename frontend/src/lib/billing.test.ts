import { describe, expect, it } from 'vitest';
import type { BillingPlans, BillingStatus } from '@/api/types';
import { amountDisplay, annualSaving, endSentence, exactDateTime, expiryNotice, payLabel, repositoryName, shortDateTime, zoneLabel } from './billing';

const KOLKATA = 'Asia/Kolkata';
const at = (iso: string) => Date.parse(iso);

const plans: BillingPlans = {
  enabled: true,
  keyId: 'rzp_test_x',
  currency: 'INR',
  prices: [
    { period: 'MONTHLY', amountPaise: 70000, display: '700 INR', autoRenewAvailable: true },
    { period: 'YEARLY', amountPaise: 700000, display: '7000 INR', autoRenewAvailable: true },
  ],
  free: { repositoryAnalyses: 5, aiQuestionsPerWeek: 15 },
  premium: { repositoryAnalyses: null, aiQuestionsPerWeek: null },
  aiQuestionsPerMinute: 10,
  keepRepositories: 5,
  graceDays: 3,
  reminderDays: 3,
};

function status(overrides: Partial<BillingStatus>): BillingStatus {
  return {
    plan: 'PREMIUM',
    effectivePlan: 'PREMIUM',
    planExpiresAt: '2026-11-23T10:00:00Z',
    state: 'ACTIVE',
    autoRenew: { active: false, period: null, cancelAtCycleEnd: false, nextChargeAt: null },
    grace: null,
    ...overrides,
  };
}

describe('exact dates with the zone name', () => {
  it('writes the weekday, the date, the 24-hour time and the zone', () => {
    expect(exactDateTime('2026-11-27T06:28:00Z', KOLKATA)).toBe('Friday, 27 November 2026 at 11:58 (IST)');
    expect(exactDateTime('2026-11-27T06:28:00Z', 'UTC')).toBe('Friday, 27 November 2026 at 06:28 (UTC)');
    expect(exactDateTime('2026-11-27T06:28:00Z', 'America/New_York')).toBe('Friday, 27 November 2026 at 01:28 (EST)');
  });

  it('falls back to the offset for a zone without a letter abbreviation', () => {
    expect(zoneLabel(at('2026-11-27T06:28:00Z'), 'Asia/Tokyo')).toBe('GMT+9');
    expect(zoneLabel(at('2026-11-27T06:28:00Z'), 'Europe/Berlin')).toBe('CET');
  });

  it('has a short form and is empty for a missing or broken value', () => {
    expect(shortDateTime('2026-11-27T06:28:00Z', KOLKATA)).toBe('27 Nov 2026, 11:58 (IST)');
    expect(exactDateTime(null)).toBe('');
    expect(exactDateTime('nope')).toBe('');
  });

  it('says Ends on, Ended on or No end date', () => {
    const now = at('2026-11-20T10:00:00Z');
    expect(endSentence('2026-11-27T06:28:00Z', now, KOLKATA)).toBe('Ends on Friday, 27 November 2026 at 11:58 (IST)');
    expect(endSentence('2026-11-19T06:28:00Z', now, KOLKATA)).toBe('Ended on Thursday, 19 November 2026 at 11:58 (IST)');
    expect(endSentence(null, now, KOLKATA)).toBe('No end date');
  });
});

describe('prices from the server', () => {
  it('formats paise as the server does', () => {
    expect(amountDisplay(70000, 'INR')).toBe('700 INR');
    expect(amountDisplay(700000, 'INR')).toBe('7,000 INR');
    expect(amountDisplay(70050, 'INR')).toBe('700.50 INR');
  });

  it('computes the annual saving from the two prices', () => {
    // 12 x 700 = 8400 against 7000: 1400 saved, 16%
    expect(annualSaving(plans)).toEqual({ paise: 140000, percent: 16 });
    const noSaving = { ...plans, prices: plans.prices.map((p) => (p.period === 'YEARLY' ? { ...p, amountPaise: 840000 } : p)) };
    expect(annualSaving(noSaving)).toBeNull();
  });

  it('labels the button with the price for a one-time payment', () => {
    expect(payLabel(plans, 'MONTHLY', 'once')).toBe('Pay 700 INR');
    expect(payLabel(plans, 'YEARLY', 'once')).toBe('Pay 7,000 INR');
    expect(payLabel(plans, 'YEARLY', 'auto')).toBe('Start auto-renew');
  });
});

describe('the expiry notices', () => {
  const now = at('2026-11-20T10:00:00Z');

  it('says nothing for ACTIVE, NONE and LAPSED', () => {
    expect(expiryNotice(status({ state: 'ACTIVE' }), 5, now, KOLKATA)).toBeNull();
    expect(expiryNotice(status({ state: 'NONE', plan: 'FREE', effectivePlan: 'FREE', planExpiresAt: null }), 5, now, KOLKATA)).toBeNull();
    expect(expiryNotice(status({ state: 'LAPSED', plan: 'FREE', effectivePlan: 'FREE', planExpiresAt: null }), 5, now, KOLKATA)).toBeNull();
    expect(expiryNotice(undefined, 5, now)).toBeNull();
  });

  it('counts days like the Ask AI reset, and names today or tomorrow under a day', () => {
    const grace = { endsAt: '2026-11-26T10:00:00Z', repositoriesAtRisk: 0 };
    expect(expiryNotice(status({ state: 'EXPIRING', grace }), 5, now, KOLKATA)?.title).toBe('Your Premium ends in 3 days');
    expect(expiryNotice(status({ state: 'EXPIRING', planExpiresAt: '2026-11-20T12:00:00Z', grace }), 5, now, KOLKATA)?.title).toBe(
      'Your Premium ends today at 17:30',
    );
    expect(expiryNotice(status({ state: 'EXPIRING', planExpiresAt: '2026-11-20T19:00:00Z', grace }), 5, now, KOLKATA)?.title).toBe(
      'Your Premium ends tomorrow at 00:30',
    );
  });

  it('mentions deletion only when repositories are at risk', () => {
    const safe = expiryNotice(status({ state: 'EXPIRING', grace: { endsAt: '2026-11-26T10:00:00Z', repositoriesAtRisk: 0 } }), 5, now, KOLKATA);
    expect(safe?.body).not.toMatch(/delet/);
    const risky = expiryNotice(status({ state: 'EXPIRING', grace: { endsAt: '2026-11-26T10:00:00Z', repositoriesAtRisk: 7 } }), 5, now, KOLKATA);
    expect(risky?.body).toBe(
      'It does not renew by itself. If it ends, only your 5 most recently used repositories are kept after a 3-day grace period, and 7 repositories would be deleted.',
    );
  });

  it('in the grace period says when the deletion happens, in the same words', () => {
    const notice = expiryNotice(
      status({ state: 'GRACE', effectivePlan: 'FREE', planExpiresAt: '2026-11-19T10:00:00Z', grace: { endsAt: '2026-11-22T10:00:00Z', repositoriesAtRisk: 1 } }),
      5,
      now,
      KOLKATA,
    );
    expect(notice).toMatchObject({ tone: 'error', title: 'Your Premium ended on 19 Nov 2026, 15:30 (IST)', atRisk: 1 });
    expect(notice?.body).toBe('1 repository will be deleted in 2 days, keeping your 5 most recently used. Renew before then to keep everything.');
  });
});

it('names a repository owner/repo', () => {
  expect(repositoryName('https://github.com/spring-projects/spring-petclinic')).toBe('spring-projects/spring-petclinic');
  expect(repositoryName('https://github.com/a/b/')).toBe('a/b');
});
