import { expect, test } from '@playwright/test';
import { env, requireLive, signIn, usedAnalyses } from './helpers';

test('register and verify by email', async ({ page: _page }, info) => {
  // The backend sends verification mail through a real SMTP relay; there is no dev mail path to read the link from.
  info.skip(true, 'needs a readable mailbox (dev mail path or seeded account); verify-email is covered by unit tests and manual runs');
});

test('analyse a repository: progress, graph, inspector, files, relationships, delete, usage', async ({ page }, info) => {
  await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD');
  const repo = env.ARCHYRA_E2E_REPO || env.ARCHYRA_CAPTURE_REPO;
  await signIn(page, env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD);
  const before = await usedAnalyses(page);
  const limit = Number(await page.locator('aside[aria-label="Sidebar"] [role="meter"]').getAttribute('aria-valuemax'));
  info.skip(before >= limit, 'the test account has no free analyses left');

  await page.getByLabel('Paste a GitHub URL').fill(repo);
  await page.getByRole('button', { name: 'Analyze' }).click();
  await expect(page).toHaveURL(/\/analyses\/[0-9a-f-]{36}$/);
  const id = page.url().split('/').pop() as string;
  await expect(page.getByRole('progressbar', { name: 'Analysis progress' })).toBeVisible();
  await expect.poll(async () => usedAnalyses(page), { timeout: 30_000 }).toBe(before + 1);

  // The progress view hands over to the overview by itself when the analysis completes.
  await expect(page).toHaveURL(new RegExp(`/analyses/${id}/overview$`), { timeout: 200_000 });
  await expect(page.getByText('Classes', { exact: true })).toBeVisible();

  await page.getByRole('link', { name: 'Architecture' }).click();
  const node = page.locator('.react-flow__node').first();
  await expect(node).toBeVisible();
  await node.click();
  await expect(page).toHaveURL(/node=/);
  const inspector = page.getByRole('complementary', { name: 'Node details' });
  await expect(inspector.getByText('Outgoing')).toBeVisible();
  await page.keyboard.press('Escape');

  await page.getByRole('link', { name: 'Files', exact: true }).click();
  await page.locator('tbody tr').first().click();
  await expect(page.getByRole('complementary', { name: 'File details' }).getByText('Imports')).toBeVisible();

  await page.getByRole('link', { name: 'Relationships' }).click();
  await page.locator('section[aria-label="Classes and interfaces"] li button').first().click();
  await expect(page.getByText('Depended on by')).toBeVisible();

  await page.getByRole('button', { name: 'More actions' }).click();
  await page.getByRole('menuitem', { name: 'Delete analysis' }).click();
  await page.getByRole('button', { name: 'Delete analysis' }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  // Deleting gives a free user the credit back.
  await expect.poll(async () => usedAnalyses(page), { timeout: 15_000 }).toBe(before);
  await page.goto(`/analyses/${id}`);
  await expect(page.getByRole('heading', { name: 'Analysis not found' })).toBeVisible();
});

test('sign out ends the session and protects the dashboard', async ({ page }, info) => {
  await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD');
  await signIn(page, env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD);
  await page.goto('/settings');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/dashboard');
  await expect(page).toHaveURL(/\/login\?next=%2Fdashboard$/);
});

test('admin gating: a user gets the ordinary 404 and no Admin link', async ({ page }, info) => {
  await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD');
  await signIn(page, env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD);
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link')).toHaveText(['Dashboard', 'Settings']);
  for (const path of ['/admin', '/admin/stats', '/admin/users', '/admin/users/anything']) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page not found');
    await expect(page.locator('aside[aria-label="Sidebar"]')).toHaveCount(0);
  }
});

test('admin gating: an ADMIN sees the admin area', async ({ page }, info) => {
  await requireLive(page, info, 'ARCHYRA_ADMIN_EMAIL', 'ARCHYRA_ADMIN_PASSWORD');
  await signIn(page, env.ARCHYRA_ADMIN_EMAIL, env.ARCHYRA_ADMIN_PASSWORD);
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Admin' })).toBeVisible();
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/admin\/stats$/);
  await expect(page.locator('.stat')).toHaveCount(4);
  await page.getByRole('link', { name: 'Users' }).click();
  await expect(page.locator('tbody tr').first()).toBeVisible();
});

test('anonymous visitors: /admin is a 404, workspace routes ask to sign in', async ({ page }, info) => {
  await requireLive(page, info);
  await page.goto('/admin/users');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page not found');
  await page.goto('/dashboard');
  await expect(page).toHaveURL(/\/login\?next=/);
});
