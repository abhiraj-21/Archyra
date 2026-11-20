import { expect, test, type Page } from '@playwright/test';
import { env, literal, requireLive, signIn, sql } from './helpers';

/**
 * Premium through Razorpay (spec/razorpay-premium), against the running stack and the shared test account:
 *
 *  - the payment screen shows the server's real numbers and the comparison, and the period and mode controls change the button;
 *  - opening the checkout loads Razorpay's checkout.js and shows its frame, only when the backend has Razorpay TEST keys
 *    (`GET /billing/plans` says `enabled`); otherwise it skips and says so. Closing the frame pays nothing;
 *  - the expiry banner, the dialog after sign-in, the exact end time in Settings and the Cancel auto-renew dialog, for the test
 *    account with its plan row seeded directly in the development database (the e2e database helper `sql`, no test endpoint).
 *    Opt-in: set ARCHYRA_E2E_DB_CONTAINER (e.g. archyra-postgres). The account is put back to FREE afterwards.
 *
 * Nothing here completes a payment: a real test-mode payment is the manual checklist in backend/README.md.
 */

async function openUpgrade(page: Page) {
  const plansResponse = page.waitForResponse((res) => res.url().endsWith('/api/v1/billing/plans') && res.status() === 200);
  await page.goto('/upgrade');
  const plans = (await (await plansResponse).json()) as {
    enabled: boolean;
    currency: string;
    prices: { period: string; amountPaise: number; autoRenewAvailable: boolean }[];
    free: { repositoryAnalyses: number; aiQuestionsPerWeek: number };
    keepRepositories: number;
    graceDays: number;
  };
  await expect(page.getByRole('heading', { name: 'Archyra Premium', level: 1 })).toBeVisible();
  return plans;
}

const rupees = (paise: number) => (paise / 100).toLocaleString('en-IN');

test.describe('the payment screen', () => {
  test.beforeEach(async ({ page }, info) => {
    await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD');
    await signIn(page, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
  });

  test('shows the real plan numbers and the Free and Premium comparison', async ({ page }) => {
    const plans = await openUpgrade(page);
    const table = page.getByRole('table', { name: 'Free and Premium compared' });
    await expect(table).toContainText(`${plans.free.repositoryAnalyses} in total`);
    await expect(table).toContainText(`${plans.free.aiQuestionsPerWeek} per week`);
    await expect(table).toContainText(`only your ${plans.keepRepositories} most recently used repositories`);
    await expect(table).toContainText(`${plans.graceDays}-day grace period`);
    await expect(table).toContainText('Coming soon');
    if (!plans.enabled) {
      await expect(page.getByText('Payments are not available right now')).toBeVisible();
      await expect(page.getByTestId('pay-button')).toHaveCount(0);
    }
  });

  test('the period and the payment type change the button', async ({ page }, info) => {
    const plans = await openUpgrade(page);
    info.skip(!plans.enabled, 'billing is off on this backend (no Razorpay keys): there is no pay button');
    const monthly = plans.prices.find((p) => p.period === 'MONTHLY');
    const yearly = plans.prices.find((p) => p.period === 'YEARLY');
    const button = page.getByTestId('pay-button');

    await expect(button).toHaveText(`Pay ${rupees(monthly?.amountPaise ?? 0)} ${plans.currency}`);
    await page.getByRole('radio', { name: /^Annual/ }).click();
    await expect(button).toHaveText(`Pay ${rupees(yearly?.amountPaise ?? 0)} ${plans.currency}`);
    if (yearly?.autoRenewAvailable) {
      await page.getByRole('radio', { name: 'Auto-renew' }).click();
      await expect(button).toHaveText('Start auto-renew');
      await page.getByRole('radio', { name: 'One-time payment' }).click();
    }
    await expect(page.getByRole('radiogroup', { name: 'Payment type' })).toBeVisible();
  });

  test('opening the checkout loads checkout.js and shows the Razorpay frame', async ({ page }, info) => {
    const before = await page.evaluate(() => Array.from(document.scripts).some((s) => s.src.includes('checkout.razorpay.com')));
    expect(before, 'checkout.js is never loaded outside the payment screen').toBe(false);
    const plans = await openUpgrade(page);
    info.skip(!plans.enabled, 'billing is off on this backend: set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET (TEST keys) to run this');
    expect(await page.evaluate(() => Array.from(document.scripts).some((s) => s.src.includes('checkout.razorpay.com'))), 'not before the button').toBe(false);

    const script = page.waitForRequest((req) => req.url().startsWith('https://checkout.razorpay.com/v1/checkout.js'));
    await page.getByTestId('pay-button').click();
    await script;
    await expect(page.frameLocator('iframe.razorpay-checkout-frame').locator('body')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('status').filter({ hasText: 'Complete the payment in the Razorpay window.' })).toBeVisible();
  });
});

test.describe('expiry notices for a seeded plan (database helper)', () => {
  test.beforeEach(async ({ page }, info) => {
    await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD', 'ARCHYRA_E2E_DB_CONTAINER');
  });

  const email = () => literal((env.ARCHYRA_TEST_EMAIL as string).toLowerCase());

  test.afterEach(() => {
    if (!env.ARCHYRA_E2E_DB_CONTAINER) return;
    sql(`DELETE FROM subscriptions WHERE user_id = (SELECT id FROM users WHERE email = ${email()})`);
    sql(`UPDATE users SET plan = 'FREE', plan_expires_at = NULL WHERE email = ${email()}`);
  });

  test('banner on every page, the dialog once after sign-in, and the exact end in Settings', async ({ page }) => {
    // ends in two days at a whole minute, so the exact time can be compared
    const end = sql(
      `UPDATE users SET plan = 'PREMIUM', plan_expires_at = date_trunc('minute', now()) + interval '2 days' WHERE email = ${email()} RETURNING to_char(plan_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')`,
    ).split('\n')[0] as string;

    await signIn(page, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
    const dialog = page.getByRole('dialog', { name: 'Your Premium ends in 2 days' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Later' }).click();
    await expect(dialog).toBeHidden();

    const banner = page.getByTestId('expiry-banner');
    await expect(banner).toContainText('Your Premium ends in 2 days');
    await expect(banner.getByRole('link', { name: 'Renew' })).toHaveAttribute('href', '/upgrade');
    await page.goto('/settings');
    await expect(banner).toBeVisible();
    const expected = await page.evaluate((iso) => {
      const at = Date.parse(iso);
      const date = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(at);
      const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(at);
      return `Ends on ${date} at ${time}`;
    }, end);
    await expect(page.getByTestId('plan-end')).toContainText(expected);

    // a reload is a silent session refresh, not a sign-in: no dialog
    await page.reload();
    await expect(page.getByTestId('expiry-banner')).toBeVisible();
    await expect(page.getByRole('dialog', { name: /Your Premium ends/ })).toHaveCount(0);
  });

  test('the Cancel auto-renew dialog says when Premium then ends', async ({ page }) => {
    sql(`UPDATE users SET plan = 'PREMIUM', plan_expires_at = now() + interval '20 days' WHERE email = ${email()}`);
    sql(`INSERT INTO subscriptions (id, user_id, razorpay_subscription_id, razorpay_plan_id, period, status, cancel_at_cycle_end, current_end, created_at, updated_at)
         SELECT gen_random_uuid(), id, 'sub_E2eSeeded' || substr(md5(random()::text), 1, 8), 'plan_e2e', 'MONTHLY', 'ACTIVE', false, now() + interval '20 days', now(), now()
         FROM users WHERE email = ${email()}`);
    await signIn(page, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
    await page.goto('/settings#subscription');
    await expect(page.getByText(/Auto-renew is on; the next charge is on/)).toBeVisible();

    await page.getByRole('button', { name: 'Cancel auto-renew' }).click();
    const dialog = page.getByRole('dialog', { name: 'Cancel auto-renew?' });
    await expect(dialog).toContainText('You will not be charged again. Premium stays until');
    await expect(dialog).toContainText(/\(\w+[+-]?[\d:]*\)/);
    // the seeded subscription does not exist at Razorpay: keep it rather than ask Razorpay to cancel it
    await dialog.getByRole('button', { name: 'Keep auto-renew' }).click();
    await expect(dialog).toBeHidden();
  });
});
