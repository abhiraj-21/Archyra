import { expect, test } from '@playwright/test';
import { env, requireLive } from './helpers';

/**
 * Sign-in plumbing that only shows up through the real dev proxy. Skips, saying why, when the stack is not running.
 */

for (const provider of ['google', 'github'] as const) {
  test(`OAuth: ${provider} is sent the frontend origin as redirect_uri when started through the dev server`, async ({ page }, info) => {
    await requireLive(page, info);
    // Redirects off: the Location header is the thing under test (it points at the real provider).
    const res = await page.request.get(`/oauth2/authorization/${provider}`, { maxRedirects: 0 });
    expect(res.status()).toBe(302);
    const location = new URL(res.headers().location as string);
    expect(location.hostname).toBe(provider === 'google' ? 'accounts.google.com' : 'github.com');
    const redirectUri = new URL(location.searchParams.get('redirect_uri') as string);
    // The browser origin, not the backend's own :8080 and not 127.0.0.1: the provider must have exactly this registered.
    expect(redirectUri.origin).toBe(new URL(env.ARCHYRA_APP_URL as string).origin);
    expect(redirectUri.pathname).toBe(`/login/oauth2/code/${provider}`);
  });
}

test('registering the same address twice gives the same answer and the same screen', async ({ page }, info) => {
  await requireLive(page, info);
  // Creates an unverified account in the dev database and spends two of the five hourly register/reset attempts of this
  // IP, so it is opt-in: ARCHYRA_E2E_REGISTER=1.
  info.skip(env.ARCHYRA_E2E_REGISTER !== '1', 'opt-in: set ARCHYRA_E2E_REGISTER=1 (creates an unverified dev account, uses 2 register attempts)');
  const email = `e2e-dup-${Date.now()}@example.com`;
  const password = 'correct horse battery';

  const screens: string[] = [];
  const bodies: unknown[] = [];
  for (const attempt of [1, 2]) {
    await page.goto('/register');
    await page.getByLabel('Name').fill('E2E Duplicate');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    const response = page.waitForResponse((r) => r.url().endsWith('/api/v1/auth/register'));
    await page.getByRole('button', { name: 'Create account' }).click();
    const r = await response;
    expect(r.status(), `attempt ${attempt}`).toBe(202);
    bodies.push(await r.json());
    await expect(page).toHaveURL(/\/register\/check-email$/);
    await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible();
    screens.push((await page.locator('main').innerText()).replace(email, '<email>'));
  }

  expect(bodies[1]).toEqual(bodies[0]);
  expect(screens[1]).toBe(screens[0]);
  for (const text of screens) {
    expect(text).not.toMatch(/already (registered|exists|in use|taken)/i);
    expect(text).toMatch(/If this address can be registered/);
  }
  await expect(page.getByRole('link', { name: 'Sign in' }).first()).toHaveAttribute('href', '/login');
  await expect(page.getByRole('link', { name: 'reset your password' })).toHaveAttribute('href', '/forgot-password');
});
