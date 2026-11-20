import type { BillingPlans, BillingPrice, BillingStatus, BillingPeriod } from '@/api/types';
import { whenPhrase } from './resetTime';

/**
 * Pure helpers of the Premium screens: exact dates with the zone name, prices and the annual saving from the server's numbers
 * (nothing is hard-coded), and the wording of the expiry notices. `now` and `timeZone` are parameters (default: the browser's),
 * so the tests pin them.
 */

/**
 * The zone's short name in a form people read: "IST", "EST", "CET", "UTC", or the offset ("GMT+9") when no locale has a
 * letter abbreviation for it. Intl writes Asia/Kolkata as "GMT+5:30" in en-US and en-GB and as "IST" only in en-IN, so a few
 * English locales are tried in turn.
 */
export function zoneLabel(at: number, timeZone?: string): string {
  let fallback = '';
  for (const locale of ['en-US', 'en-GB', 'en-IN']) {
    const name = new Intl.DateTimeFormat(locale, { timeZone, timeZoneName: 'short' }).formatToParts(at).find((part) => part.type === 'timeZoneName')?.value ?? '';
    if (/^[A-Z]{2,5}$/.test(name)) return name;
    if (!fallback) fallback = name;
  }
  return fallback;
}

/** "Friday, 27 November 2026 at 11:58 (IST)": the exact moment in the reader's zone, with the zone's name. Empty for a bad value. */
export function exactDateTime(iso: string | null | undefined, timeZone?: string): string {
  if (!iso) return '';
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return '';
  const date = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone }).format(at);
  const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone }).format(at);
  return `${date} at ${time} (${zoneLabel(at, timeZone)})`;
}

/** "27 Nov 2026, 11:58 (IST)": the same moment, short, for tables and one-line summaries. */
export function shortDateTime(iso: string | null | undefined, timeZone?: string): string {
  if (!iso) return '';
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return '';
  const date = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone }).format(at);
  const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone }).format(at);
  return `${date}, ${time} (${zoneLabel(at, timeZone)})`;
}

/** "Ends on ...", "Ended on ..." or "No end date", for the Settings plan line. */
export function endSentence(planExpiresAt: string | null, now: number = Date.now(), timeZone?: string): string {
  if (!planExpiresAt) return 'No end date';
  const ended = Date.parse(planExpiresAt) <= now;
  return `${ended ? 'Ended' : 'Ends'} on ${exactDateTime(planExpiresAt, timeZone)}`;
}

/** "700 INR" (or "700.50 INR") from paise. */
export function amountDisplay(paise: number, currency: string): string {
  const whole = Math.floor(paise / 100);
  const rest = paise % 100;
  return `${rest === 0 ? whole.toLocaleString('en-IN') : `${whole.toLocaleString('en-IN')}.${String(rest).padStart(2, '0')}`} ${currency}`;
}

export function priceOf(plans: BillingPlans, period: BillingPeriod): BillingPrice | undefined {
  return plans.prices.find((price) => price.period === period);
}

/**
 * What a year costs less than twelve months, from the server's prices: `{ paise, percent }` (percent rounded down), or null when
 * the annual price is not cheaper.
 */
export function annualSaving(plans: BillingPlans): { paise: number; percent: number } | null {
  const monthly = priceOf(plans, 'MONTHLY');
  const yearly = priceOf(plans, 'YEARLY');
  if (!monthly || !yearly) return null;
  const twelve = monthly.amountPaise * 12;
  const paise = twelve - yearly.amountPaise;
  if (paise <= 0) return null;
  return { paise, percent: Math.floor((paise / twelve) * 100) };
}

/** The label of the pay button: "Pay 700 INR" for a one-time payment, "Start auto-renew" otherwise. */
export function payLabel(plans: BillingPlans, period: BillingPeriod, mode: 'once' | 'auto'): string {
  if (mode === 'auto') return 'Start auto-renew';
  const price = priceOf(plans, period);
  return price ? `Pay ${amountDisplay(price.amountPaise, plans.currency)}` : 'Pay';
}

/** The notice for an ending or ended Premium, or null when there is nothing to warn about (NONE, ACTIVE, LAPSED). */
export interface ExpiryNotice {
  tone: 'warn' | 'error';
  title: string;
  body: string;
  /** How many repositories would be deleted (0: nothing is). */
  atRisk: number;
}

export function expiryNotice(status: BillingStatus | undefined, keep: number, now: number = Date.now(), timeZone?: string): ExpiryNotice | null {
  if (!status || !status.planExpiresAt) return null;
  const atRisk = status.grace?.repositoriesAtRisk ?? 0;
  const repositories = atRisk === 1 ? '1 repository' : `${atRisk} repositories`;
  if (status.state === 'EXPIRING') {
    const when = whenPhrase(status.planExpiresAt, now, timeZone) ?? '';
    const body =
      atRisk > 0
        ? `It does not renew by itself. If it ends, only your ${keep} most recently used repositories are kept after a ${graceWords(status)} grace period, and ${repositories} would be deleted.`
        : 'It does not renew by itself. Renew to keep Premium.';
    return { tone: 'warn', title: `Your Premium ends ${when}`, body, atRisk };
  }
  if (status.state === 'GRACE') {
    const deletion = whenPhrase(status.grace?.endsAt, now, timeZone) ?? '';
    const body =
      atRisk > 0
        ? `${capitalise(repositories)} will be deleted ${deletion}, keeping your ${keep} most recently used. Renew before then to keep everything.`
        : 'You are on the Free plan now. Renew whenever you like; your repositories are not affected.';
    return { tone: atRisk > 0 ? 'error' : 'warn', title: `Your Premium ended on ${shortDateTime(status.planExpiresAt, timeZone)}`, body, atRisk };
  }
  return null;
}

/** "3-day", from the server's end and deletion instants. */
function graceWords(status: BillingStatus): string {
  if (!status.grace || !status.planExpiresAt) return '';
  const days = Math.round((Date.parse(status.grace.endsAt) - Date.parse(status.planExpiresAt)) / 86_400_000);
  return `${days}-day`;
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** "owner/repo" of a GitHub URL. */
export function repositoryName(url: string): string {
  const parts = url.replace(/\/+$/, '').split('/');
  return parts.length >= 2 ? `${parts[parts.length - 2]}/${parts[parts.length - 1]}` : url;
}
