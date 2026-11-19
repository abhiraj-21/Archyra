/**
 * The dark theme in a real browser against the real stack: the default, the header toggle, no flash on reload,
 * Settings > Appearance, System following the device live, keyboard use, and every main screen in dark.
 * (The light theme staying pixel-identical is scripts/e2e/visual.spec.ts.)
 */
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
// @ts-expect-error: plain ESM module shared with the capture CLI
import { PUBLIC_SCREENS, adminScreens, discover, openScreen, signInViaUi, userScreens } from '../capture/screens.mjs';
import { env } from './helpers';

const KEY = 'archyra.theme';
const theme = (page: Page) => page.evaluate(() => document.documentElement.getAttribute('data-theme'));
const colourScheme = (page: Page) => page.evaluate(() => getComputedStyle(document.documentElement).colorScheme);
const stored = (page: Page) => page.evaluate((k) => localStorage.getItem(k), KEY);

async function visitor(browser: Browser, baseURL: string, saved?: string): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, reducedMotion: 'reduce', colorScheme: 'light' });
  if (saved) await context.addInitScript(([k, v]) => localStorage.setItem(k as string, v as string), [KEY, saved]);
  return context;
}

test.describe('theme: signed out', () => {
  test('is light by default, with nothing stored', async ({ browser, baseURL }) => {
    const context = await visitor(browser, baseURL as string);
    const page = await context.newPage();
    await page.goto('/login');
    expect(await theme(page)).toBe('light');
    expect(await colourScheme(page)).toBe('light');
    expect(await stored(page)).toBeNull();
    await context.close();
  });

  test('the header toggle switches to dark, and the choice survives a reload with no flash', async ({ browser, baseURL }) => {
    const context = await visitor(browser, baseURL as string);
    // Records what <html data-theme> already is when the document has been parsed, before React has run at all.
    await context.addInitScript(() => {
      document.addEventListener('DOMContentLoaded', () => {
        (window as unknown as { __themeAtDcl: string | null }).__themeAtDcl = document.documentElement.getAttribute('data-theme');
      });
    });
    const page = await context.newPage();
    await page.goto('/');
    const toggle = page.getByRole('button', { name: 'Dark theme' });
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(await theme(page)).toBe('dark');
    expect(await colourScheme(page)).toBe('dark');
    expect(await stored(page)).toBe('dark');

    await page.reload({ waitUntil: 'domcontentloaded' });
    expect(await page.evaluate(() => (window as unknown as { __themeAtDcl: string | null }).__themeAtDcl)).toBe('dark');
    await expect(page.getByRole('button', { name: 'Dark theme' })).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('button', { name: 'Dark theme' }).click();
    expect(await theme(page)).toBe('light');
    expect(await stored(page)).toBe('light');
    await context.close();
  });

  test('a blocked or invalid store is light and breaks nothing', async ({ browser, baseURL }) => {
    const context = await visitor(browser, baseURL as string, 'purple');
    const errors: string[] = [];
    const page = await context.newPage();
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto('/login');
    expect(await theme(page)).toBe('light');
    await page.close();

    const blocked = await browser.newContext({ baseURL: baseURL as string });
    await blocked.addInitScript(() => {
      Object.defineProperty(window, 'localStorage', { get() { throw new Error('SecurityError'); } });
    });
    const page2 = await blocked.newPage();
    page2.on('pageerror', (e) => errors.push(e.message));
    await page2.goto('/');
    expect(await theme(page2)).toBe('light');
    await page2.getByRole('button', { name: 'Dark theme' }).click();
    expect(await theme(page2)).toBe('dark'); // for this page, even though it cannot be remembered
    expect(errors).toEqual([]);
    await context.close();
    await blocked.close();
  });

  test('System follows the device, live', async ({ browser, baseURL }) => {
    const context = await visitor(browser, baseURL as string, 'system');
    const page = await context.newPage();
    await page.emulateMedia({ colorScheme: 'light' });
    await page.goto('/login');
    expect(await theme(page)).toBe('light');
    await page.emulateMedia({ colorScheme: 'dark' });
    await expect.poll(() => theme(page)).toBe('dark');
    expect(await colourScheme(page)).toBe('dark');
    await page.emulateMedia({ colorScheme: 'light' });
    await expect.poll(() => theme(page)).toBe('light');
    // and System is already right before first paint on the next load
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect(await theme(page)).toBe('dark');
    await context.close();
  });

  test('the toggle is labelled and works from the keyboard', async ({ browser, baseURL }) => {
    const context = await visitor(browser, baseURL as string);
    const page = await context.newPage();
    await page.goto('/login');
    const toggle = page.getByRole('button', { name: 'Dark theme' });
    await expect(toggle).toHaveAttribute('title', 'Switch to the dark theme');
    await toggle.focus();
    await expect(toggle).toBeFocused();
    await page.keyboard.press('Enter');
    expect(await theme(page)).toBe('dark');
    await expect(toggle).toHaveAttribute('title', 'Switch to the light theme');
    await page.keyboard.press('Space');
    expect(await theme(page)).toBe('light');
    await context.close();
  });
});

test.describe('theme: signed in', () => {
  let ctx: { analysisId: string; focusId: string } | null = null;
  let skip = '';
  // eslint-disable-next-line no-empty-pattern
  test.beforeAll(async ({}) => {
    if (!env.ARCHYRA_TEST_EMAIL || !env.ARCHYRA_TEST_PASSWORD) skip = 'set ARCHYRA_TEST_EMAIL and ARCHYRA_TEST_PASSWORD in .env.local';
    else {
      try {
        ctx = await discover(env);
      } catch (e) {
        skip = (e as Error).message;
      }
    }
  });

  test('Settings > Appearance chooses Light, Dark and System and shows the stored value', async ({ browser, baseURL }) => {
    test.skip(!!skip, skip);
    const context = await visitor(browser, baseURL as string);
    const page = await context.newPage();
    await signInViaUi(page, env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD);
    await page.goto('/settings');
    const group = page.getByRole('radiogroup', { name: 'Theme' });
    await expect(group.getByRole('radio', { name: 'Light' })).toHaveAttribute('aria-checked', 'true');

    await group.getByRole('radio', { name: 'Dark' }).click();
    expect(await theme(page)).toBe('dark');
    expect(await stored(page)).toBe('dark');
    await page.reload();
    await expect(page.getByRole('radiogroup', { name: 'Theme' }).getByRole('radio', { name: 'Dark' })).toHaveAttribute('aria-checked', 'true');

    await page.emulateMedia({ colorScheme: 'dark' });
    await page.getByRole('radiogroup', { name: 'Theme' }).getByRole('radio', { name: 'System' }).click();
    expect(await stored(page)).toBe('system');
    expect(await theme(page)).toBe('dark');
    await page.emulateMedia({ colorScheme: 'light' });
    await expect.poll(() => theme(page)).toBe('light');
    await page.reload();
    await expect(page.getByRole('radiogroup', { name: 'Theme' }).getByRole('radio', { name: 'System' })).toHaveAttribute('aria-checked', 'true');

    // keyboard: arrows move and select
    await page.getByRole('radio', { name: 'System' }).focus();
    await page.keyboard.press('ArrowLeft');
    expect(await stored(page)).toBe('dark');
    await page.getByRole('radiogroup', { name: 'Theme' }).getByRole('radio', { name: 'Light' }).click();
    expect(await theme(page)).toBe('light');
    await context.close();
  });

  test('the shell has the toggle (wide and narrow) and it is the same choice as Settings', async ({ browser, baseURL }) => {
    test.skip(!!skip, skip);
    const context = await visitor(browser, baseURL as string);
    const page = await context.newPage();
    await signInViaUi(page, env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD);
    await page.getByRole('button', { name: 'Dark theme' }).click();
    expect(await theme(page)).toBe('dark');
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole('button', { name: 'Dark theme' }).filter({ visible: true })).toHaveAttribute('aria-pressed', 'true');
    await page.goto('/settings');
    await expect(page.getByRole('radio', { name: 'Dark' })).toHaveAttribute('aria-checked', 'true');
    await context.close();
  });

  test('every main screen renders in dark, without console errors, with its own colours', async ({ browser, baseURL }) => {
    test.skip(!!skip || !ctx, skip || 'no analysis');
    test.setTimeout(480_000);
    const size = { label: '1440', width: 1440, height: 900 };
    const errors: string[] = [];
    const watch = (page: Page, name: string) => {
      page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
      // The browser itself logs every 401/404 network answer (a signed-out visitor's session refresh is a 401): not app errors.
      page.on('console', (m) => m.type() === 'error' && !/Failed to load resource/.test(m.text()) && errors.push(`${name}: ${m.text()}`));
    };

    const probe = (page: Page) =>
      page.evaluate(() => {
        const bg = (sel: string) => {
          const el = document.querySelector(sel);
          return el ? getComputedStyle(el).backgroundColor : null;
        };
        return { body: bg('body'), panel: bg('.panel'), primary: bg('.btn-primary'), scheme: getComputedStyle(document.documentElement).colorScheme, theme: document.documentElement.getAttribute('data-theme') };
      });

    const admin = !!env.ARCHYRA_ADMIN_EMAIL && !!env.ARCHYRA_ADMIN_PASSWORD;
    const groups: { label: string; who: 'none' | 'user' | 'admin'; screens: { name: string; path: string }[] }[] = [
      { label: 'public', who: 'none', screens: PUBLIC_SCREENS },
      { label: 'user', who: 'user', screens: userScreens(ctx) },
      ...(admin ? [{ label: 'admin', who: 'admin' as const, screens: adminScreens() }] : []),
    ];
    for (const group of groups) {
      // Fresh contexts per group: a signed-in context is redirected away from /login.
      const darkCtx = await visitor(browser, baseURL as string, 'dark');
      const lightCtx = await visitor(browser, baseURL as string);
      if (group.who !== 'none') {
        const [email, password] = group.who === 'admin' ? [env.ARCHYRA_ADMIN_EMAIL, env.ARCHYRA_ADMIN_PASSWORD] : [env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD];
        for (const c of [darkCtx, lightCtx]) {
          const p = await c.newPage();
          await signInViaUi(p, email, password);
          await p.close();
        }
      }
      for (const screen of group.screens) {
        const results: Record<string, Awaited<ReturnType<typeof probe>>> = {};
        for (const [mode, c] of [['dark', darkCtx], ['light', lightCtx]] as const) {
          const page = await c.newPage();
          watch(page, `${screen.name} (${mode})`);
          await openScreen(page, screen, size);
          results[mode] = await probe(page);
          await page.close();
        }
        const d = results.dark as Awaited<ReturnType<typeof probe>>;
        const l = results.light as Awaited<ReturnType<typeof probe>>;
        expect(d.theme, screen.name).toBe('dark');
        expect(d.scheme, `${screen.name} color-scheme`).toBe('dark');
        expect(l.scheme, `${screen.name} light color-scheme`).toBe('light');
        expect(d.body, `${screen.name} body`).not.toBe(l.body);
        if (d.panel !== null) expect(d.panel, `${screen.name} .panel`).not.toBe(l.panel);
        if (d.primary !== null) expect(d.primary, `${screen.name} .btn-primary`).not.toBe(l.primary);
      }
      await darkCtx.close();
      await lightCtx.close();
    }
    expect(errors).toEqual([]);
  });
});
