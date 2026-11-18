import { expect, test, type Page } from '@playwright/test';
import { env, requireLive, signIn } from './helpers';

/**
 * Settings → Sign-in methods (spec/oauth-login-hydration-and-account-linking, Task 2). Google and GitHub cannot be
 * automated, so the browser's trip to the provider is cut at the provider boundary: the navigation to
 * /oauth2/authorization/<provider>?link_intent=… is observed and aborted. The way back is tested by loading the
 * return URLs the backend redirects to. Unlinking needs a linked identity, which no real provider round trip can
 * create here and no test-only endpoint may seed, so it is covered by the backend tests and listed as manual.
 */

async function openSettings(page: Page, path = '/settings') {
  await page.goto(path);
  await expect(page.getByRole('heading', { name: 'Sign-in methods' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Sign-in methods' })).toBeVisible();
}

test.describe('Settings → Sign-in methods', () => {
  test.beforeEach(async ({ page }, info) => {
    await requireLive(page, info, 'ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD');
    // The linked-identities endpoint exists only on a backend new enough for this feature.
    await signIn(page, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
    const probe = await page.evaluate(async () => (await fetch('/api/v1/users/me/linked-identities')).status);
    info.skip(probe === 404 || probe === 403, 'the running backend does not have the linked-identities endpoints yet');
  });

  test('shows both providers with their linked state and a Link action for an unlinked one', async ({ page }) => {
    await openSettings(page);
    for (const label of ['Google', 'GitHub']) {
      const row = page.getByRole('listitem').filter({ hasText: new RegExp(`^\\s*${label}`) });
      await expect(row).toBeVisible();
      // the state is always text, never colour alone
      await expect(row.getByText(/^(Linked|Not linked)$/)).toBeVisible();
      await expect(row.getByRole('button', { name: /^(Link|Unlink)$/ })).toBeVisible();
    }
  });

  test('Link asks for an intent, sets the binding cookie and navigates to the provider start URL', async ({ page, context }, info) => {
    await openSettings(page);
    const google = page.getByRole('listitem').filter({ hasText: /^\s*Google/ });
    info.skip((await google.getByText('Linked', { exact: true }).count()) > 0, 'the test account already has Google linked');

    let started: string | null = null;
    await page.route('**/oauth2/authorization/**', async (route) => {
      started = route.request().url(); // the provider boundary: observe, never follow
      await route.abort();
    });
    const intent = page.waitForResponse((r) => r.url().endsWith('/users/me/linked-identities/google/link-intent') && r.request().method() === 'POST');
    await google.getByRole('button', { name: 'Link' }).click();
    const response = await intent;
    expect(response.status()).toBe(200);

    await expect.poll(() => started).toMatch(/\/oauth2\/authorization\/google\?link_intent=[A-Za-z0-9_-]{20,}$/);
    const cookie = (await context.cookies()).find((c) => c.name === 'link_binding');
    expect(cookie, 'the binding cookie').toBeTruthy();
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe('Strict');
    expect(cookie?.path).toBe('/oauth2/authorization');
    // the token that travels in the URL is not the cookie's secret
    expect(started).not.toContain(cookie?.value as string);
  });

  test('a successful return shows a toast and removes the parameters', async ({ page }) => {
    await openSettings(page, '/settings?link=ok&provider=google');
    await expect(page.getByRole('status').filter({ hasText: 'Google is now linked to your account.' })).toBeVisible();
    await expect(page).toHaveURL(/\/settings$/);
  });

  test('a failed return shows a dismissible banner with fixed copy and removes the parameters', async ({ page }) => {
    await openSettings(page, '/settings?link_error=identity_already_linked');
    const banner = page.getByRole('alert').filter({ hasText: 'already linked to a different Archyra account' });
    await expect(banner).toBeVisible();
    await expect(banner).not.toContainText('@'); // never names the other account
    await expect(page).toHaveURL(/\/settings$/);
    await banner.getByRole('button', { name: 'Dismiss' }).click();
    await expect(banner).toHaveCount(0);
  });

  test('an unknown link_error code gets the generic copy and the raw value is never rendered', async ({ page }) => {
    await openSettings(page, '/settings?link_error=%3Cb%3Eevil%3C%2Fb%3E');
    await expect(page.getByRole('alert').filter({ hasText: 'The link did not complete.' })).toBeVisible();
    await expect(page.locator('main')).not.toContainText('evil');
    await expect(page).toHaveURL(/\/settings$/);
  });
});
