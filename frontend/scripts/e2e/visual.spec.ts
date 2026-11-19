/**
 * The key regression test for the dark-theme work: the LIGHT theme must stay pixel-identical.
 * Every screen in scripts/capture/screens.mjs is photographed (full page, 1440 and 390 wide, reduced motion) and
 * compared with the committed baseline in scripts/e2e/__baseline__/ with ZERO tolerance (see playwright.config.ts).
 *
 *   npm run e2e -- visual                      compare
 *   npm run e2e -- visual --update-snapshots   re-record the baseline (only when light is meant to change)
 *
 * The baseline PNGs are rendered by macOS Chromium and show the account they were recorded with (display name "Dark Theme", one
 * COMPLETED spring-petclinic analysis). On another OS or account, check out the commit before the dark theme (git worktree), record the
 * baseline there with --update-snapshots, and compare from the current code.
 * The theme controls (the header toggle, Settings > Appearance: everything marked data-theme-ui) did not exist when the
 * baseline was recorded, so this test removes exactly those and holds every other pixel to the baseline.
 * The admin user list is live data, so it is removed too (`volatile` in screens.mjs).
 * Runs against the real stack; skips, saying why, without the test account or a COMPLETED analysis.
 */
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
// @ts-expect-error: plain ESM module shared with the capture CLI
import { PUBLIC_SCREENS, WIDTHS, adminScreens, captureOptions, discover, openScreen, signInViaUi, userScreens } from '../capture/screens.mjs';
import { env } from './helpers';

type Size = { label: string; width: number; height: number };
type Screen = { name: string; path: string; mask?: (page: Page) => unknown[]; viewportOnlyAt?: string[]; volatile?: string[] };

test.describe.configure({ mode: 'serial' });

let baseURL: string;
let ctx: { analysisId: string; focusId: string } | null = null;
let skipReason = '';
const contexts: BrowserContext[] = [];

// eslint-disable-next-line no-empty-pattern
test.beforeAll(async ({}, info) => {
  baseURL = info.project.use.baseURL as string;
  if (!env.ARCHYRA_TEST_EMAIL || !env.ARCHYRA_TEST_PASSWORD) {
    skipReason = 'set ARCHYRA_TEST_EMAIL and ARCHYRA_TEST_PASSWORD in .env.local';
    return;
  }
  try {
    ctx = await discover(env);
  } catch (e) {
    skipReason = (e as Error).message;
  }
});
test.afterAll(async () => {
  for (const c of contexts) await c.close().catch(() => {});
});

async function lightContext(browser: import('@playwright/test').Browser) {
  const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce', colorScheme: 'light' });
  contexts.push(context);
  return context;
}

/** A fresh tab per screenshot (the signed-in session lives in the context), so no render state leaks between screens. */
async function shot(context: BrowserContext, screen: Screen, size: Size) {
  const page = await context.newPage();
  try {
    await openScreen(page, screen, size, { lightRegression: true });
    await expect(page).toHaveScreenshot(`${screen.name}-${size.label}.png`, captureOptions(screen, size, page));
  } finally {
    await page.close();
  }
}

test.describe('light theme is unchanged: signed out', () => {
  let context: BrowserContext;
  test.beforeAll(async ({ browser }) => {
    context = await lightContext(browser);
  });
  for (const size of WIDTHS as Size[]) {
    for (const screen of PUBLIC_SCREENS as Screen[]) {
      test(`${screen.name} at ${size.label}`, async () => {
        test.skip(!!skipReason, skipReason);
        await shot(context, screen, size);
      });
    }
  }
});

test.describe('light theme is unchanged: signed in', () => {
  let context: BrowserContext;
  test.beforeAll(async ({ browser }) => {
    if (skipReason || !ctx) return;
    context = await lightContext(browser);
    const page = await context.newPage();
    await signInViaUi(page, env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD);
    await page.close();
  });
  for (const size of WIDTHS as Size[]) {
    // The names are fixed, the paths need the analysis, so the list is built lazily inside each test.
    for (const name of userScreens({ analysisId: 'x', focusId: 'x' }).map((s: Screen) => s.name)) {
      test(`${name} at ${size.label}`, async () => {
        test.skip(!!skipReason || !ctx, skipReason || 'no analysis');
        const screen = userScreens(ctx!).find((s: Screen) => s.name === name) as Screen;
        await shot(context, screen, size);
      });
    }
  }
});

test.describe('light theme is unchanged: admin', () => {
  let context: BrowserContext;
  test.beforeAll(async ({ browser }) => {
    if (skipReason || !env.ARCHYRA_ADMIN_EMAIL || !env.ARCHYRA_ADMIN_PASSWORD) return;
    context = await lightContext(browser);
    const page = await context.newPage();
    await signInViaUi(page, env.ARCHYRA_ADMIN_EMAIL, env.ARCHYRA_ADMIN_PASSWORD);
    await page.close();
  });
  for (const size of WIDTHS as Size[]) {
    for (const screen of adminScreens() as Screen[]) {
      test(`${screen.name} at ${size.label}`, async () => {
        test.skip(!!skipReason || !env.ARCHYRA_ADMIN_EMAIL || !env.ARCHYRA_ADMIN_PASSWORD, skipReason || 'set ARCHYRA_ADMIN_EMAIL and ARCHYRA_ADMIN_PASSWORD in .env.local');
        await shot(context, screen, size);
      });
    }
  }
});
