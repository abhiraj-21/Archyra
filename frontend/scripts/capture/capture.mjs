#!/usr/bin/env node
/**
 * npm run assets:capture
 *
 * Produces the PNGs in src/assets/ from the REAL running app and backend (spec 13). Never imported by the app.
 *
 * Needs: the backend on its usual port, the Vite dev server (`npm run dev`), and an untracked .env.local with a
 * dedicated test account (ARCHYRA_TEST_EMAIL / ARCHYRA_TEST_PASSWORD). It analyses ARCHYRA_CAPTURE_REPO
 * (spring-petclinic by default) as that user, re-runs it once to photograph the progress view mid-run, and shoots
 * the workspace on the finished analysis. Chromium at deviceScaleFactor 2, reduced motion so nothing is caught
 * mid-animation.
 *
 * Privacy: only the test user's own analysis is shown. Settings, the account menu and admin screens are never
 * captured, so no email address appears in any image. The admin account is refused outright.
 *
 *   node scripts/capture/capture.mjs            # everything
 *   node scripts/capture/capture.mjs --only hero,graph-layered
 */
import fs from 'node:fs';
import path from 'node:path';
import { chromium, request } from '@playwright/test';
import sharp from 'sharp';
import { frontendRoot, loadEnv } from '../lib/env.mjs';

const env = loadEnv();
const APP = env.ARCHYRA_APP_URL.replace(/\/$/, '');
const REPO = env.ARCHYRA_CAPTURE_REPO;
const OUT = path.join(frontendRoot, 'src', 'assets');
const VIEWPORT = { width: 1440, height: 900 };
const SCALE = 2;
const FOCUS_CLASS = env.ARCHYRA_CAPTURE_FOCUS ?? 'OwnerController';
const only = (() => {
  const i = process.argv.indexOf('--only');
  return i > -1 ? new Set(process.argv[i + 1].split(',')) : null;
})();
const want = (name) => !only || only.has(name);

function fail(message) {
  console.error(`assets:capture: ${message}`);
  process.exit(1);
}

if (!env.ARCHYRA_TEST_EMAIL || !env.ARCHYRA_TEST_PASSWORD) fail('set ARCHYRA_TEST_EMAIL and ARCHYRA_TEST_PASSWORD in .env.local (a test account, never a real person).');
if (env.ARCHYRA_ADMIN_EMAIL && env.ARCHYRA_TEST_EMAIL.toLowerCase() === env.ARCHYRA_ADMIN_EMAIL.toLowerCase()) fail('the capture account must not be the admin account.');

// ---------- API, as the test user, through the app origin (the Vite proxy) ----------
const api = await request.newContext({ baseURL: APP });
async function call(method, url, body, token) {
  const res = await api.fetch(`/api/v1${url}`, { method, data: body, headers: token ? { Authorization: `Bearer ${token}` } : {} });
  const text = await res.text();
  return { status: res.status(), body: text ? JSON.parse(text) : undefined };
}
const login = await call('POST', '/auth/login', { email: env.ARCHYRA_TEST_EMAIL, password: env.ARCHYRA_TEST_PASSWORD });
if (login.status !== 200) fail(`test login failed (${login.status} ${login.body?.code ?? ''}). Is the backend running and the app at ${APP}?`);
const token = login.body.accessToken;
const me = await call('GET', '/users/me', undefined, token);
if (me.body?.role === 'ADMIN') fail('the capture account has role ADMIN; use a normal test account.');

async function waitForTerminal(id, label) {
  for (let i = 0; i < 600; i++) {
    const s = await call('GET', `/analyses/${id}`, undefined, token);
    if (s.body?.status === 'COMPLETED') return s.body;
    if (s.body?.status === 'FAILED') fail(`${label} failed: ${s.body.failureReason ?? 'no reason given'}`);
    await new Promise((r) => setTimeout(r, 1000));
  }
  fail(`${label} did not finish in time`);
}

async function findCaptureAnalysis() {
  const list = await call('GET', '/analyses?page=0&size=50', undefined, token);
  return (list.body?.items ?? []).find((a) => a.repositoryUrl === REPO && a.status === 'COMPLETED');
}

let analysis = await findCaptureAnalysis();
if (!analysis) {
  console.log(`Analysing ${REPO} for the captures…`);
  const created = await call('POST', '/analyses', { repositoryUrl: REPO }, token);
  if (created.status !== 202) fail(`could not start the analysis (${created.status} ${created.body?.code ?? ''})`);
  await waitForTerminal(created.body.analysisId, 'the capture analysis');
  analysis = { analysisId: created.body.analysisId };
}

// ---------- browser ----------
fs.mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();
const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: SCALE, reducedMotion: 'reduce', colorScheme: 'light' });
const page = await context.newPage();
page.on('pageerror', (e) => console.warn('page error:', e.message));

async function settle() {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(700);
}

async function save(name, buffer, { maxWidth } = {}) {
  let img = sharp(buffer);
  if (maxWidth) img = img.resize({ width: maxWidth, withoutEnlargement: true });
  const out = await img.png({ compressionLevel: 9, palette: true, quality: 92, effort: 10 }).toBuffer();
  fs.writeFileSync(path.join(OUT, `${name}.png`), out);
  const meta = await sharp(out).metadata();
  console.log(`  ${name}.png  ${meta.width}x${meta.height}  ${(out.length / 1024).toFixed(0)} KB`);
}

const shotPage = async (name) => save(name, await page.screenshot({ type: 'png' }));
const shotEl = async (name, selector) => save(name, await page.locator(selector).first().screenshot({ type: 'png' }));

await page.goto(`${APP}/login`);
await page.getByLabel('Email').fill(env.ARCHYRA_TEST_EMAIL);
await page.getByLabel('Password', { exact: true }).fill(env.ARCHYRA_TEST_PASSWORD);
await page.getByRole('button', { name: 'Sign in' }).click();
await page.waitForURL('**/dashboard');

// The progress view, mid-run: re-run the analysis (it replaces the old one when it completes).
if (want('analysis-progress')) {
  const rerun = await call('POST', `/analyses/${analysis.analysisId}/reanalyze`, undefined, token);
  if (rerun.status !== 202) fail(`could not re-run the analysis (${rerun.status} ${rerun.body?.code ?? ''})`);
  const id = rerun.body.analysisId;
  await page.goto(`${APP}/analyses/${id}`);
  // The run takes seconds: sample the progress view while it is on screen and keep the frame furthest along
  // (completed stages and an active one) taken before the page moves on to the overview.
  let best = null;
  for (let i = 0; i < 400; i++) {
    if (!page.url().endsWith(`/analyses/${id}`)) break;
    const active = await page.locator('.step[data-state="active"]').count();
    const done = await page.locator('.step[data-state="done"]').count();
    if (active === 1 && done >= 1 && (!best || done > best.done)) {
      const shot = await page.screenshot({ type: 'png' }).catch(() => null);
      if (shot && page.url().endsWith(`/analyses/${id}`)) best = { done, shot };
    }
    if (best?.done >= 3) break;
    await page.waitForTimeout(120);
  }
  if (!best) fail('the re-run finished before its progress view could be photographed; run the capture again');
  await save('analysis-progress', best.shot);
  await waitForTerminal(id, 'the re-run');
  analysis = { analysisId: id };
}

const base = `${APP}/analyses/${analysis.analysisId}`;
const graphData = await call('GET', `/analyses/${analysis.analysisId}/graph`, undefined, token);
const focus = (graphData.body?.nodes ?? []).find((n) => n.type === 'CLASS' && n.name === FOCUS_CLASS) ?? (graphData.body?.nodes ?? []).find((n) => n.type === 'CLASS');
if (!focus) fail('the capture graph has no classes');
const focusParam = `node=${encodeURIComponent(focus.id)}`;

// Workspace with the inspector open: sidebar, header, tabs, graph.
if (want('workspace-preview') || want('inspector-panel')) {
  // Class view (classes and interfaces joined by Depends on, Extends and Implements): the default view is the module graph,
  // which would not contain the selected class.
  await page.goto(`${base}/architecture?nodes=CLASS,INTERFACE&edges=DEPENDS_ON,EXTENDS,IMPLEMENTS&${focusParam}`);
  await page.locator('.react-flow__node').first().waitFor();
  await page.locator('.inspector dl').first().waitFor();
  await settle();
  if (want('workspace-preview')) await shotPage('workspace-preview');
  if (want('inspector-panel')) await shotEl('inspector-panel', '.inspector');
}

// The canvas alone: layered graph, no inspector.
if (want('graph-layered') || want('hero')) {
  await page.goto(`${base}/architecture?edges=DEPENDS_ON,IMPLEMENTS`);
  await page.locator('.react-flow__node').first().waitFor();
  await settle();
  if (want('graph-layered')) await shotEl('graph-layered', '.graph-canvas');

  // The hero: the module dependency graph (the layered architecture at package level), on the real canvas,
  // cropped to the connected modules so no card is cut by the frame. Zoom controls and minimap are hidden.
  if (want('hero')) {
    const moduleEdges = (graphData.body?.edges ?? []).filter((e) => e.type === 'DEPENDS_ON' && e.source.startsWith('module:') && e.target.startsWith('module:'));
    const linked = [...new Set(moduleEdges.flatMap((e) => [e.source, e.target]))];
    if (!linked.length) fail('the capture repository has no module dependencies to compose the hero from');
    await page.goto(`${base}/architecture?nodes=MODULE&edges=DEPENDS_ON`);
    await page.locator('.react-flow__node').first().waitFor();
    await settle();
    await page.addStyleTag({ content: '.graph-toolbar, .graph-minimap { visibility: hidden !important; }' });
    const boxes = [];
    for (const id of linked) {
      const box = await page.locator(`.react-flow__node[data-id="${id.replace(/"/g, '\\"')}"]`).boundingBox();
      if (box) boxes.push(box);
    }
    const canvas = await page.locator('.graph-canvas').boundingBox();
    const left = Math.min(...boxes.map((b) => b.x));
    const top = Math.min(...boxes.map((b) => b.y));
    const right = Math.max(...boxes.map((b) => b.x + b.width));
    const bottom = Math.max(...boxes.map((b) => b.y + b.height));
    const width = Math.max(right - left + 160, 820);
    const height = Math.max(bottom - top + 120, width * 0.62);
    const cx = (left + right) / 2;
    const cy = (top + bottom) / 2;
    const clip = {
      x: Math.max(canvas.x, cx - width / 2),
      y: Math.max(canvas.y, cy - height / 2),
      width: Math.min(width, canvas.width),
      height: Math.min(height, canvas.height),
    };
    await save('hero', await page.screenshot({ clip, type: 'png' }));
  }
}

// Files with the detail drawer.
if (want('files-table')) {
  const fileId = focus.file ? `file:${focus.file}` : null;
  await page.goto(`${base}/files${fileId ? `?file=${encodeURIComponent(fileId)}` : ''}`);
  await page.locator('tbody tr').first().waitFor();
  if (fileId) await page.locator('.inspector dl').first().waitFor();
  await settle();
  await shotPage('files-table');
}

// The class browser with a class open.
if (want('relationships-browser')) {
  await page.goto(`${base}/relationships?item=${encodeURIComponent(focus.id)}`);
  await page.getByRole('heading', { name: focus.name }).first().waitFor();
  await settle();
  await shotPage('relationships-browser');
}

// Favicon: the Archyra mark from the landing header, rendered by the app itself.
if (want('favicon')) {
  const iconPage = await (await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 8 })).newPage();
  await iconPage.goto(`${APP}/`);
  await iconPage.locator('header svg').first().waitFor();
  const mark = await iconPage.locator('header svg').first().screenshot({ omitBackground: true });
  const icon = await sharp(mark).resize(52, 52, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } }).extend({ top: 6, bottom: 6, left: 6, right: 6, background: { r: 0, g: 0, b: 0, alpha: 0 } }).png().toBuffer();
  await save('favicon', icon);
}

await browser.close();
await api.dispose();
console.log(`Done. Assets written to ${path.relative(frontendRoot, OUT)}/`);
