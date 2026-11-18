import { expect, test, type Page, type Request } from '@playwright/test';
import { env, requireLive } from './helpers';

/**
 * Session boot regressions (spec/oauth-login-hydration-and-account-linking, Task 1). Google and GitHub cannot be
 * automated, so the state after the provider redirect is reproduced directly: the browser holds the refresh cookie
 * (set here by a real API login), has no XSRF-TOKEN cookie, and makes a full page load of /auth/callback.
 * Route interception only injects network faults; it never fakes data. Runs against the dev server (StrictMode on).
 */

const REFRESH = '**/api/v1/auth/refresh';

async function loginViaApi(page: Page) {
  const res = await page.request.post('/api/v1/auth/login', {
    data: { email: env.ARCHYRA_TEST_EMAIL, password: env.ARCHYRA_TEST_PASSWORD },
  });
  expect(res.status(), 'API login').toBe(200);
  // requireLive's csrf probe shares this cookie jar; the post-redirect browser has no XSRF-TOKEN yet.
  await page.context().clearCookies({ name: 'XSRF-TOKEN' });
  const names = (await page.context().cookies()).map((c) => c.name);
  expect(names).toContain('refresh_token');
  expect(names).not.toContain('XSRF-TOKEN');
}

function countRequests(page: Page, suffix: string, method = 'POST') {
  const seen: Request[] = [];
  page.on('request', (r) => {
    if (r.method() === method && new URL(r.url()).pathname === `/api/v1/auth/${suffix}`) seen.push(r);
  });
  return seen;
}

test('callback hydration: a fresh /auth/callback with only the refresh cookie lands on the dashboard after one refresh', async ({ page }, info) => {
  await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD');
  await loginViaApi(page);
  const refreshes = countRequests(page, 'refresh');

  await page.goto('/auth/callback');
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('heading', { name: /welcome back/i })).toBeVisible({ timeout: 5_000 });
  await expect(page.getByText('Opening Archyra')).toHaveCount(0);
  expect(refreshes, 'POST /auth/refresh calls in one page load').toHaveLength(1);
});

test('hang guard: a refresh that never answers shows the retry screen after the boot timeout, and Try again works', async ({ page }, info) => {
  await requireLive(page, info);
  await page.clock.install();
  let release: (() => Promise<void>) | null = null;
  let hold = true;
  await page.route(REFRESH, async (route) => {
    if (hold) {
      release = async () => route.abort(); // the hung request is cancelled by the app; this only settles the handler
      return; // never fulfilled while held
    }
    await route.fallback();
  });

  await page.goto('/dashboard');
  await expect(page.getByText('Opening Archyra')).toBeVisible();
  await page.clock.fastForward(11_000); // past BOOT_TIMEOUT_MS, no real 10-second wait
  await expect(page.getByRole('heading', { name: 'Cannot reach Archyra' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Back to sign in' })).toBeVisible();

  hold = false;
  void release;
  await page.getByRole('button', { name: 'Try again' }).click();
  // Anonymous visitor on /dashboard: the retry boots normally (401) and the guard sends them to sign in.
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByText('Opening Archyra')).toHaveCount(0);
});

test('hang guard: "Back to sign in" leaves the retry screen for /login', async ({ page }, info) => {
  await requireLive(page, info);
  await page.clock.install();
  await page.route(REFRESH, () => {
    /* never answers */
  });
  await page.goto('/');
  await page.clock.fastForward(11_000);
  await page.getByRole('button', { name: 'Back to sign in' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
});

test('failed callback: a 401 from refresh on /auth/callback goes to /login?error=oauth_failed', async ({ page }, info) => {
  await requireLive(page, info);
  await page.route(REFRESH, (route) =>
    route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'REFRESH_TOKEN_INVALID', message: 'x' }) }),
  );
  const refreshes = countRequests(page, 'refresh');
  await page.goto('/auth/callback');
  await expect(page).toHaveURL(/\/login\?error=oauth_failed$/);
  await expect(page.getByText('Opening Archyra')).toHaveCount(0);
  expect(refreshes, 'a 401 is never retried').toHaveLength(1);
});

test('CSRF retry: a 403 CSRF_TOKEN_INVALID on the first refresh costs one csrf refetch and one retry, then the dashboard', async ({ page }, info) => {
  await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD');
  await loginViaApi(page);
  let injected = false;
  await page.route(REFRESH, async (route) => {
    if (!injected) {
      injected = true;
      await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ code: 'CSRF_TOKEN_INVALID', message: 'x' }) });
      return;
    }
    await route.fallback();
  });
  const csrfFetches = countRequests(page, 'csrf', 'GET');
  const refreshes = countRequests(page, 'refresh');

  await page.goto('/auth/callback');
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('heading', { name: /welcome back/i })).toBeVisible({ timeout: 5_000 });
  expect(csrfFetches, 'GET /auth/csrf: the boot fetch plus one refetch').toHaveLength(2);
  expect(refreshes, 'POST /auth/refresh: the rejected one plus one retry').toHaveLength(2);
});
