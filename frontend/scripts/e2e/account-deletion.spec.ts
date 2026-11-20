import { expect, test, type Page } from '@playwright/test';
import { env, registerAndVerify, requireLive, signIn, usedAnalyses } from './helpers';

/**
 * Settings → Danger zone (spec/account-deletion). Two groups:
 *
 *  - the confirm dialog and its DELETE gate, on the shared test account, which is never deleted;
 *  - the whole round trip on a throwaway account: it deletes itself with its password, cannot sign in any more, registers
 *    again and still shows the analysis it used. That needs a readable mailbox for the verification links (a MailHog sink,
 *    see backend/README.md; set ARCHYRA_E2E_MAIL_API to its address, e.g. http://localhost:8025) and spends one free
 *    analysis of the throwaway account on a small repository, so it is opt-in with ARCHYRA_E2E_DELETE=1.
 */

const PASSWORD = 'correct horse battery staple';

async function openDangerZone(page: Page) {
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: 'Danger zone' })).toBeVisible();
  await page.getByRole('button', { name: 'Delete account' }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete your account?' });
  await expect(dialog).toBeVisible();
  return dialog;
}

test.describe('Settings → Danger zone: the confirm dialog', () => {
  test.beforeEach(async ({ page }, info) => {
    await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD');
    await signIn(page, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
  });

  test('says what is deleted and that usage is not reset, and the DELETE word gates the button', async ({ page }) => {
    const dialog = await openDangerZone(page);
    await expect(dialog).toContainText('deleted permanently');
    await expect(dialog).toContainText('Your usage is not reset');
    await expect(dialog).toContainText('Paid plans are not carried over');
    const confirm = dialog.getByRole('button', { name: /^(Delete account|Email me a confirmation link)$/ });
    const hasPassword = (await dialog.getByLabel('Your password').count()) > 0;

    await expect(confirm).toBeDisabled();
    if (hasPassword) await dialog.getByLabel('Your password').fill('whatever the test account has');
    await expect(confirm).toBeDisabled(); // a password alone does not enable it
    await dialog.getByLabel('Type DELETE to confirm').fill('delete');
    await expect(confirm).toBeDisabled(); // the word is case sensitive
    await dialog.getByLabel('Type DELETE to confirm').fill('DELETE');
    await expect(confirm).toBeEnabled();
    await dialog.getByLabel('Type DELETE to confirm').fill('DELETE ');
    await expect(confirm).toBeDisabled();
  });

  test('Escape closes the dialog and reopening starts empty; Cancel closes it too', async ({ page }) => {
    const dialog = await openDangerZone(page);
    await dialog.getByLabel('Type DELETE to confirm').fill('DELETE');
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await page.getByRole('button', { name: 'Delete account' }).click();
    await expect(page.getByRole('dialog', { name: 'Delete your account?' }).getByLabel('Type DELETE to confirm')).toHaveValue('');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page).toHaveURL(/\/settings$/);
  });

  test('keeps focus inside the dialog while it is open', async ({ page }) => {
    const dialog = await openDangerZone(page);
    for (let i = 0; i < 8; i++) {
      await page.keyboard.press('Tab');
      expect(await dialog.evaluate((el) => el.contains(document.activeElement))).toBe(true);
    }
  });

  test('a wrong password is refused with its own message and nothing is deleted', async ({ page }, info) => {
    const dialog = await openDangerZone(page);
    info.skip((await dialog.getByLabel('Your password').count()) === 0, 'the test account has no password');
    await dialog.getByLabel('Your password').fill('definitely not the password');
    await dialog.getByLabel('Type DELETE to confirm').fill('DELETE');
    const attempt = page.waitForResponse((r) => r.url().endsWith('/users/me/delete'));
    await dialog.getByRole('button', { name: 'Delete account' }).click();
    const status = (await attempt).status();
    info.skip(status === 404 || status === 405, 'the running backend does not have the delete endpoint yet');
    expect(status).toBe(401);
    await expect(dialog.getByText('That is not your password. Nothing was deleted.')).toBeVisible();
    await expect(page).toHaveURL(/\/settings$/);
    await page.goto('/dashboard'); // still signed in
    await expect(page).toHaveURL(/\/dashboard$/);
  });

  test('the emailed-link page refuses a link with no token and offers the way back', async ({ page }) => {
    await page.goto('/settings/delete-confirm');
    await expect(page.getByRole('heading', { name: 'Confirm account deletion' })).toBeVisible();
    await expect(page.getByText('This link has expired')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Delete my account permanently' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Back to Settings' })).toBeVisible();
  });

  test('the emailed-link page drops the token from the address bar and a bad one deletes nothing', async ({ page }, info) => {
    await page.goto('/settings/delete-confirm?token=not-a-real-token-0123456789');
    await expect(page).toHaveURL(/\/settings\/delete-confirm$/);
    const attempt = page.waitForResponse((r) => r.url().endsWith('/users/me/delete-confirm'));
    await page.getByRole('button', { name: 'Delete my account permanently' }).click();
    const status = (await attempt).status();
    info.skip(status === 404 || status === 405, 'the running backend does not have the delete endpoint yet');
    expect([400, 401]).toContain(status);
    await expect(page.getByText('Nothing was deleted', { exact: false })).toBeVisible();
    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/dashboard$/);
  });
});

// ---- the throwaway account ---------------------------------------------------------------------

test('a throwaway account deletes itself, cannot sign in, and returns with the usage it had used', async ({ page, request }, info) => {
  const api = env.ARCHYRA_E2E_MAIL_API;
  info.skip(env.ARCHYRA_E2E_DELETE !== '1' || !api, 'opt in with ARCHYRA_E2E_DELETE=1 and ARCHYRA_E2E_MAIL_API=<MailHog address>: it registers an account and spends one free analysis of it');
  await requireLive(page, info);
  const email = `e2e-delete-${Date.now()}@example.com`;
  const repo = env.ARCHYRA_E2E_SMALL_REPO || 'https://github.com/spring-guides/gs-rest-service';

  await registerAndVerify(page, request, api as string, email, PASSWORD);
  await signIn(page, email, PASSWORD);
  await page.getByLabel('Paste a GitHub URL').fill(repo);
  await page.getByRole('button', { name: 'Analyze' }).click();
  await expect(page).toHaveURL(/\/analyses\/[0-9a-f-]{36}\/overview$/, { timeout: 200_000 });
  await page.goto('/dashboard');
  expect(await usedAnalyses(page)).toBe(1);

  // delete it with its password
  const dialog = await openDangerZone(page);
  const confirm = dialog.getByRole('button', { name: 'Delete account' });
  await expect(confirm).toBeDisabled();
  await dialog.getByLabel('Your password').fill(PASSWORD);
  await expect(confirm).toBeDisabled();
  await dialog.getByLabel('Type DELETE to confirm').fill('DELETE');
  await expect(confirm).toBeEnabled();
  await confirm.click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByText('Account deleted', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Dismiss' }).click();
  await expect(page.getByText('Account deleted', { exact: true })).toHaveCount(0);

  // the account is gone
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText('Sign-in failed')).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);

  // registering the same address again keeps what it had used
  await registerAndVerify(page, request, api as string, email, PASSWORD);
  await signIn(page, email, PASSWORD);
  expect(await usedAnalyses(page)).toBe(1);
});
