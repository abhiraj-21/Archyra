import { expect, test, type Page } from '@playwright/test';
import { env, registerAndVerify, requireLive, signIn, usedAnalyses } from './helpers';

/**
 * A repository restored to a returning user (spec/account-deletion, amendment): the real account flow end to end.
 *
 * A throwaway account analyses two small repositories (two of its free analyses, real clones from GitHub), deletes itself with
 * its password and registers again with the same address. Then:
 *  - both repositories are listed as "Not analysed" with their URL and the restored note, and the used count is unchanged;
 *  - opening one shows the empty state on every tab (no graph, no error), with Ask AI explained and disabled;
 *  - Delete returns exactly one credit (the Usage view goes down by one);
 *  - Analyse runs the other one through the normal progress view and the used count does not move.
 *
 * It needs a readable mailbox (a MailHog sink: ARCHYRA_E2E_MAIL_API, e.g. http://127.0.0.1:8025) and real analyses, so it is
 * opt-in with ARCHYRA_E2E_DELETE=1, like the account-deletion round trip, and skips with that message otherwise.
 */

const PASSWORD = 'correct horse battery staple';
const NOTE = 'Restored from your previous account. Analyse it to use the credit you already spent, or delete it to get the credit back.';

async function deleteAccount(page: Page) {
  await page.goto('/settings');
  await page.getByRole('button', { name: 'Delete account' }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete your account?' });
  await expect(dialog).toContainText('the links of the repositories you had analysed');
  await expect(dialog).toContainText('No code, graph or conversation is kept');
  await dialog.getByLabel('Your password').fill(PASSWORD);
  await dialog.getByLabel('Type DELETE to confirm').fill('DELETE');
  await dialog.getByRole('button', { name: 'Delete account' }).click();
  await expect(page).toHaveURL(/\/$/);
}

async function analyse(page: Page, repo: string) {
  await page.goto('/dashboard');
  await page.getByLabel('Paste a GitHub URL').fill(repo);
  await page.getByRole('button', { name: 'Analyze' }).click();
  await expect(page).toHaveURL(/\/analyses\/[0-9a-f-]{36}\/overview$/, { timeout: 200_000 });
}

test('a returning account gets its repositories back as not-analysed entries it can delete for a credit or analyse for free', async ({ page, request }, info) => {
  const api = env.ARCHYRA_E2E_MAIL_API;
  info.skip(
    env.ARCHYRA_E2E_DELETE !== '1' || !api,
    'opt in with ARCHYRA_E2E_DELETE=1 and ARCHYRA_E2E_MAIL_API=<MailHog address>: it registers an account and runs two real analyses of it',
  );
  info.setTimeout(600_000);
  await requireLive(page, info);
  const email = `e2e-restored-${Date.now()}@example.com`;
  const first = env.ARCHYRA_E2E_SMALL_REPO || 'https://github.com/spring-guides/gs-rest-service';
  const second = env.ARCHYRA_E2E_SMALL_REPO_2 || 'https://github.com/spring-guides/gs-serving-web-content';

  // the first life of the account
  await registerAndVerify(page, request, api as string, email, PASSWORD);
  await signIn(page, email, PASSWORD);
  await analyse(page, first);
  await analyse(page, second);
  await page.goto('/dashboard');
  expect(await usedAnalyses(page)).toBe(2);
  await deleteAccount(page);

  // the second life: same address, the usage is back, and so are the repositories, not analysed
  await registerAndVerify(page, request, api as string, email, PASSWORD);
  await signIn(page, email, PASSWORD);
  expect(await usedAnalyses(page)).toBe(2);
  const rows = page.getByRole('row').filter({ hasText: 'Not analysed' });
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toContainText(NOTE);
  await expect(page.getByRole('row').filter({ hasText: first.replace('https://', '') })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: second.replace('https://', '') })).toBeVisible();
  await expect(rows.first().getByRole('button', { name: /^Analyse / })).toBeVisible();
  await expect(rows.first().getByRole('button', { name: /^Delete / })).toBeVisible();

  // opening one: the empty state on every tab, never a broken graph, a spinner or an error
  await rows.first().getByRole('link').first().click();
  await expect(page).toHaveURL(/\/analyses\/[0-9a-f-]{36}\/overview$/);
  const panel = page.getByTestId('not-analysed-panel');
  for (const tab of ['Overview', 'Architecture', 'Files', 'Relationships', 'Ask AI']) {
    await page.getByRole('link', { name: tab, exact: true }).click();
    await expect(panel).toBeVisible();
    await expect(panel).toContainText('Not analysed yet');
    await expect(panel.getByRole('button', { name: /^Analyse / })).toBeEnabled();
    await expect(panel.getByRole('button', { name: /^Delete / })).toBeVisible();
    await expect(page.locator('.react-flow')).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
  }
  await expect(panel).toContainText('Ask AI is off until this repository has been analysed.');

  // Delete: one credit comes back
  await panel.getByRole('button', { name: /^Delete / }).click();
  const confirm = page.getByRole('dialog');
  await expect(confirm).toContainText('you have used 2 of');
  await expect(confirm).toContainText('will have used 1 of');
  await confirm.getByRole('button', { name: 'Delete repository' }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  expect(await usedAnalyses(page)).toBe(1);
  await expect(page.getByRole('row').filter({ hasText: 'Not analysed' })).toHaveCount(1);

  // Analyse the other: the normal progress view, and the used count does not move
  await page.getByRole('row').filter({ hasText: 'Not analysed' }).getByRole('button', { name: /^Analyse / }).click();
  await expect(page).toHaveURL(/\/analyses\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('progressbar').first()).toBeVisible();
  await expect(page).toHaveURL(/\/analyses\/[0-9a-f-]{36}\/overview$/, { timeout: 200_000 });
  await page.goto('/dashboard');
  expect(await usedAnalyses(page)).toBe(1);
  await expect(page.getByRole('row').filter({ hasText: 'Not analysed' })).toHaveCount(0);
  await expect(page.getByRole('row').filter({ hasText: 'Completed' })).toHaveCount(1);
});
