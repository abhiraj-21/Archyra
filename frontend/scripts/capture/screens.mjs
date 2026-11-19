#!/usr/bin/env node
/**
 * The screen list shared by the light-theme regression test and the dark preview. Never imported by the app.
 *
 *   scripts/e2e/visual.spec.ts            imports this module: every screen, light, zero-pixel diff against
 *                                         scripts/e2e/__baseline__/ (committed)
 *   node scripts/capture/screens.mjs      CLI: writes the same screens as plain PNGs
 *     --theme dark|light                  which theme to photograph (default dark: it sets localStorage archyra.theme)
 *     --out <dir>                         where to write (default scripts/e2e/__dark-preview__)
 *
 * Needs the running app and backend and the test accounts (ARCHYRA_TEST_*, optional ARCHYRA_ADMIN_*) from .env.local
 * or the process environment, exactly like scripts/capture/capture.mjs. The test account needs one COMPLETED analysis
 * (the capture repository); this script never starts one. Admin screens are photographed only when an admin account
 * is configured, and the admin tables are masked (they list real people and change with every sign-in).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, request } from '@playwright/test';
import { frontendRoot, loadEnv } from '../lib/env.mjs';

export const WIDTHS = [
  { label: '1440', width: 1440, height: 900 },
  { label: '390', width: 390, height: 844 },
];

/** Settle a page so two captures of the same state are byte-identical: no spinner, fonts loaded, images decoded. */
export async function settle(page) {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(() => !document.querySelector('.skeleton, .spinner, [aria-busy="true"]'), undefined, { timeout: 20_000 }).catch(() => {});
  // Lazy images far down the page only load when scrolled to; walk the page once, then return to the top.
  await page.evaluate(async () => {
    const step = Math.max(400, window.innerHeight - 100);
    for (let y = 0; y < document.documentElement.scrollHeight; y += step) {
      window.scrollTo(0, y);
      await new Promise((r) => setTimeout(r, 60));
    }
    window.scrollTo(0, 0);
    // Only images that are laid out can load (a lazy image inside display:none never does), and never wait for ever.
    const rendered = [...document.images].filter((img) => img.getClientRects().length > 0 && !img.complete);
    await Promise.race([Promise.all(rendered.map((img) => img.decode().catch(() => null))), new Promise((r) => setTimeout(r, 4000))]);
  });
  await page.waitForTimeout(700);
}

async function waitForGraph(page) {
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 });
  // Nodes are measured with the web font, so it must be in before the camera settles.
  await page.evaluate(() => document.fonts.ready);
  // The camera is fitted after the layout arrives: wait until the viewport transform has stopped moving.
  await page.waitForFunction(
    () => {
      const el = document.querySelector('.react-flow__viewport');
      const now = el ? getComputedStyle(el).transform : '';
      const w = window;
      const same = w.__vp === now;
      w.__vp = now;
      w.__vpStable = same ? (w.__vpStable ?? 0) + 1 : 0;
      return w.__vpStable >= 12;
    },
    undefined,
    { polling: 100, timeout: 20_000 },
  );
  await page.waitForTimeout(500);
}

/** Screens a visitor sees without signing in. */
export const PUBLIC_SCREENS = [
  { name: 'landing', path: '/' },
  { name: 'login', path: '/login' },
  { name: 'register', path: '/register' },
  { name: 'register-check-email', path: '/register/check-email' },
  { name: 'verify-email', path: '/verify-email' },
  { name: 'forgot-password', path: '/forgot-password' },
  { name: 'reset-password', path: '/reset-password' },
  { name: 'not-found', path: '/no-such-page' },
];

/** Screens of a signed-in test user, built from what the account actually has. */
export function userScreens({ analysisId, focusId }) {
  const a = `/analyses/${analysisId}`;
  const focus = encodeURIComponent(focusId);
  return [
    { name: 'dashboard', path: '/dashboard' },
    { name: 'dashboard-account-menu', path: '/dashboard', act: async (page) => {
      // Under 60rem the account menu sits in the drawer.
      const drawer = page.getByRole('button', { name: 'Open menu' });
      if (await drawer.isVisible()) await drawer.click();
      await page.getByRole('button', { name: /^Account menu/ }).filter({ visible: true }).first().click();
    } },
    { name: 'overview', path: `${a}/overview`, ready: waitForGraph },
    { name: 'architecture-modules', path: `${a}/architecture`, ready: waitForGraph },
    // At 390 the inspector is a bottom sheet over the graph, and a full-page capture rasterises the scaled edges under it
    // differently every time (a few pixels, off by 1 to 4 of 255); the viewport capture of the same page is stable.
    { name: 'architecture-classes', path: `${a}/architecture?nodes=CLASS,INTERFACE&edges=DEPENDS_ON,EXTENDS,IMPLEMENTS&node=${focus}`, ready: waitForGraph, viewportOnlyAt: ['390'] },
    { name: 'architecture-list', path: `${a}/architecture?view=list&nodes=CLASS,INTERFACE&edges=DEPENDS_ON` },
    { name: 'files', path: `${a}/files` },
    { name: 'relationships', path: `${a}/relationships?item=${focus}` },
    { name: 'ask-ai', path: `${a}/ask-ai` },
    { name: 'settings', path: '/settings' },
    { name: 'settings-delete-dialog', path: '/settings', act: async (page) => { await page.getByRole('button', { name: 'Delete account' }).click(); await page.locator('dialog[open]').waitFor(); } },
  ];
}

/** Admin screens. The tables list real accounts and change on every sign-in, so their data is masked. */
export function adminScreens() {
  const mask = (page) => [page.locator('main table, main dl, main [role="meter"], main .stat, main .stats')];
  return [
    { name: 'admin-stats', path: '/admin/stats', mask },
    // The list itself is live data (it grows with every registration), so the regression test removes it (`volatile`) and holds
    // the page around it to the baseline; the dark preview shows it.
    { name: 'admin-users', path: '/admin/users', mask, volatile: ['main table', 'main nav[aria-label="User pages"]'] },
  ];
}

/** Sign in through the real API (the way capture.mjs does) and find what the screens need. */
export async function discover(env) {
  const APP = env.ARCHYRA_APP_URL.replace(/\/$/, '');
  const api = await request.newContext({ baseURL: APP });
  const call = async (method, url, body, token) => {
    const res = await api.fetch(`/api/v1${url}`, { method, data: body, headers: token ? { Authorization: `Bearer ${token}` } : {} });
    const text = await res.text();
    return { status: res.status(), body: text ? JSON.parse(text) : undefined };
  };
  const login = await call('POST', '/auth/login', { email: env.ARCHYRA_TEST_EMAIL, password: env.ARCHYRA_TEST_PASSWORD });
  if (login.status !== 200) throw new Error(`test login failed (${login.status} ${login.body?.code ?? ''})`);
  const token = login.body.accessToken;
  const list = await call('GET', '/analyses?page=0&size=50', undefined, token);
  const analysis = (list.body?.items ?? []).find((x) => x.status === 'COMPLETED');
  if (!analysis) throw new Error('the test account has no COMPLETED analysis; analyse a repository as that user first');
  const graph = await call('GET', `/analyses/${analysis.analysisId}/graph`, undefined, token);
  const nodes = graph.body?.nodes ?? [];
  const focus = nodes.find((n) => n.type === 'CLASS' && n.name === (env.ARCHYRA_CAPTURE_FOCUS ?? 'OwnerController')) ?? nodes.find((n) => n.type === 'CLASS');
  if (!focus) throw new Error('the analysis has no classes');
  await api.dispose();
  return { app: APP, analysisId: analysis.analysisId, focusId: focus.id };
}

export async function signInViaUi(page, email, password) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL('**/dashboard');
}

/** The options every capture of a screen uses: the whole page, except where a screen says it is only stable as the viewport. */
export function captureOptions(screen, size, page) {
  return { fullPage: !(screen.viewportOnlyAt ?? []).includes(size.label), animations: 'disabled', caret: 'hide', ...(screen.mask ? { mask: screen.mask(page) } : {}) };
}

/** Open one screen at one width and bring it to the state that is photographed. `lightRegression` is for the light regression test only (see visual.spec.ts). */
export async function openScreen(page, screen, size, { lightRegression = false } = {}) {
  await page.setViewportSize({ width: size.width, height: size.height });
  await page.goto(screen.path);
  await page.locator('main, #main, body').first().waitFor();
  // Test harness only: no transition or animation can be caught half-way (the graph's edges fade on hover and selection).
  await page.addStyleTag({ content: '*, *::before, *::after { transition: none !important; animation: none !important; }' });
  // The light regression test compares against a baseline recorded before the theme controls existed (the header toggle and
  // Settings, Appearance, both marked data-theme-ui). They are new, so they are taken out; every OTHER pixel must match.
  if (lightRegression) {
    await page.addStyleTag({ content: '[data-theme-ui] { display: none !important; }' });
    // Live data (a user list that grows with every registration) cannot be held to a baseline: leave it out.
    if (screen.volatile) await page.addStyleTag({ content: `${screen.volatile.join(', ')} { display: none !important; }` });
  }
  if (screen.ready) await screen.ready(page);
  await settle(page);
  if (screen.act) {
    await screen.act(page);
    await page.waitForTimeout(450);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------------------------
async function main() {
  const arg = (name, fallback) => {
    const i = process.argv.indexOf(name);
    return i > -1 ? process.argv[i + 1] : fallback;
  };
  const theme = arg('--theme', 'dark');
  const out = path.resolve(frontendRoot, arg('--out', 'scripts/e2e/__dark-preview__'));
  const env = loadEnv();
  if (!env.ARCHYRA_TEST_EMAIL || !env.ARCHYRA_TEST_PASSWORD) throw new Error('set ARCHYRA_TEST_EMAIL and ARCHYRA_TEST_PASSWORD (a test account, never a real person)');
  const ctx = await discover(env);
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  const written = [];

  const newContext = (size) =>
    browser.newContext({ baseURL: ctx.app, viewport: { width: size.width, height: size.height }, reducedMotion: 'reduce', colorScheme: 'light' });
  const choose = async (context) => {
    // The visitor's own choice, stored the way the app stores it (nothing is stored for the default light theme).
    if (theme !== 'light') await context.addInitScript((t) => localStorage.setItem('archyra.theme', t), theme);
  };
  // A fresh tab per screen (the session lives in the context) so no render state leaks from one screen to the next.
  const shoot = async (context, screen, size) => {
    const page = await context.newPage();
    try {
      await openScreen(page, screen, size);
      const file = path.join(out, `${screen.name}-${size.label}.png`);
      await page.screenshot({ path: file, ...captureOptions(screen, size, page) });
      written.push(file);
      console.log(`  ${path.relative(frontendRoot, file)}`);
    } finally {
      await page.close();
    }
  };

  const publicContext = await newContext(WIDTHS[0]);
  await choose(publicContext);
  for (const size of WIDTHS) for (const screen of PUBLIC_SCREENS) await shoot(publicContext, screen, size);
  await publicContext.close();

  const userContext = await newContext(WIDTHS[0]);
  await choose(userContext);
  const userPage = await userContext.newPage();
  await signInViaUi(userPage, env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD);
  await userPage.close();
  for (const size of WIDTHS) for (const screen of userScreens(ctx)) await shoot(userContext, screen, size);
  await userContext.close();

  if (env.ARCHYRA_ADMIN_EMAIL && env.ARCHYRA_ADMIN_PASSWORD) {
    const adminContext = await newContext(WIDTHS[0]);
    await choose(adminContext);
    const adminPage = await adminContext.newPage();
    await signInViaUi(adminPage, env.ARCHYRA_ADMIN_EMAIL, env.ARCHYRA_ADMIN_PASSWORD);
    await adminPage.close();
    for (const size of WIDTHS) for (const screen of adminScreens()) await shoot(adminContext, screen, size);
    await adminContext.close();
  } else {
    console.log('  (no ARCHYRA_ADMIN_* account: admin screens skipped)');
  }
  await browser.close();
  console.log(`Done: ${written.length} screenshots in ${path.relative(frontendRoot, out)}/`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(`screens: ${e.message}`);
    process.exit(1);
  });
}
