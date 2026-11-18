import { expect, type Page, type TestInfo } from '@playwright/test';
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
