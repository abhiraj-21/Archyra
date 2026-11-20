import { expect, type APIRequestContext, type Page, type TestInfo } from '@playwright/test';
// @ts-expect-error: plain ESM helper shared with the capture script
import { loadEnv } from '../lib/env.mjs';

export const env = loadEnv() as Record<string, string>;

/** Skips the test unless the app answers and the named variables are set. */
export async function requireLive(page: Page, info: TestInfo, ...vars: string[]) {
  const missing = vars.filter((v) => !env[v]);
  info.skip(missing.length > 0, `set ${missing.join(', ')} in .env.local`);
  const res = await page.request.get('/api/v1/auth/csrf').catch(() => null);
  info.skip(!res || res.status() !== 200, `backend not reachable through ${env.ARCHYRA_APP_URL}`);
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
}

export async function usedAnalyses(page: Page): Promise<number> {
  const meter = page.locator('aside[aria-label="Sidebar"] [role="meter"]');
  await expect(meter).toBeVisible();
  return Number(await meter.getAttribute('aria-valuenow'));
}

/** The newest mail to {@code to} from a MailHog sink (newest first), as plain text with quoted-printable decoded. */
export async function lastMailBody(request: APIRequestContext, api: string, to: string): Promise<string> {
  let body = '';
  await expect
    .poll(
      async () => {
        const res = await request.get(`${api}/api/v2/search`, { params: { kind: 'to', query: to, limit: '50' } });
        const items = ((await res.json()) as { items: { Content: { Body: string } }[] }).items;
        body = items[0]?.Content.Body ?? '';
        return body;
      },
      { timeout: 30_000, message: `no mail for ${to} reached the sink` },
    )
    .not.toBe('');
  return body.replace(/=\r?\n/g, '').replace(/=3D/g, '=');
}

/** Registers through the UI and opens the verification link from the sink. A throwaway account only. */
export async function registerAndVerify(page: Page, request: APIRequestContext, api: string, email: string, password: string) {
  await page.goto('/register');
  await page.getByLabel('Name').fill('E2E Throwaway');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: /create account|register|sign up/i }).click();
  await expect(page).toHaveURL(/\/register\/check-email$/);
  const link = /https?:\/\/\S+\/verify-email\?token=[A-Za-z0-9_-]+/.exec(await lastMailBody(request, api, email));
  expect(link, 'the verification link').toBeTruthy();
  await page.goto(new URL(link?.[0] as string).pathname + new URL(link?.[0] as string).search);
  await expect(page.getByRole('heading', { name: 'Email verified' })).toBeVisible();
}
