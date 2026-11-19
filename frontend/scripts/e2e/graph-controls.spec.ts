import { expect, test, type BrowserContext, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { env, signIn } from './helpers';

/**
 * The Working | Tests | All scope on Architecture, Relationships and Files, and the draggable graph, against the REAL
 * backend (no mocks). They use a COMPLETED analysis of the test account that has both test and working classes, and
 * only start one (using a free analysis, returned when it is deleted at the end) when there is none. Everything skips,
 * saying why, when the stack or the credentials are missing.
 */
test.describe.configure({ mode: 'serial' });

let skipReason: string | null = null;
let analysisId = '';
let startedHere = false;
let token = '';

async function prepare(playwright: PlaywrightWorkerArgs['playwright'], baseURL: string): Promise<string | null> {
  const missing = ['ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD'].filter((v) => !env[v]);
  if (missing.length) return `set ${missing.join(', ')} in .env.local`;
  const api = await playwright.request.newContext({ baseURL });
  try {
    const csrf = await api.get('/api/v1/auth/csrf').catch(() => null);
    if (!csrf || csrf.status() !== 200) return `backend not reachable through ${baseURL}`;
    const login = await api.post('/api/v1/auth/login', { data: { email: env.ARCHYRA_TEST_EMAIL, password: env.ARCHYRA_TEST_PASSWORD } });
    if (login.status() !== 200) return 'the test account cannot sign in';
    token = ((await login.json()) as { accessToken: string }).accessToken;
    const headers = { Authorization: `Bearer ${token}` };
    const usable = async (id: string) => {
      const res = await api.get(`/api/v1/analyses/${id}/graph`, { headers });
      if (res.status() !== 200) return false;
      const graph = (await res.json()) as { nodes: { type: string; test?: boolean }[]; edges: unknown[] };
      const hasTypeField = graph.nodes.every((n) => typeof n.test === 'boolean');
      return hasTypeField && graph.nodes.some((n) => n.test) && graph.nodes.filter((n) => !n.test && n.type === 'CLASS').length >= 2 && graph.edges.length > 0;
    };
    const list = (await (await api.get('/api/v1/analyses?page=0&size=50', { headers })).json()) as { items: { analysisId: string; status: string }[] };
    for (const item of list.items.filter((i) => i.status === 'COMPLETED')) {
      if (await usable(item.analysisId)) {
        analysisId = item.analysisId;
        return null;
      }
    }
    const usage = (await (await api.get('/api/v1/usage', { headers })).json()) as { usage: { limitReached: boolean }[] };
    if (usage.usage.some((u) => u.limitReached)) return 'the test account has no completed analysis with test code and no free analysis left';
    const created = await api.post('/api/v1/analyses', { headers, data: { repositoryUrl: env.ARCHYRA_E2E_REPO || env.ARCHYRA_CAPTURE_REPO } });
    if (created.status() !== 202) return `could not start an analysis (${created.status()})`;
    const id = ((await created.json()) as { analysisId: string }).analysisId;
    startedHere = true;
    analysisId = id;
    for (let i = 0; i < 100; i++) {
      const status = (await (await api.get(`/api/v1/analyses/${id}`, { headers })).json()) as { status: string };
      if (status.status === 'COMPLETED') return (await usable(id)) ? null : 'the analysed repository has no test code to filter';
      if (status.status === 'FAILED') return 'the analysis failed';
      await new Promise((r) => setTimeout(r, 2000));
    }
    return 'the analysis did not finish in time';
  } finally {
    await api.dispose();
  }
}

/**
 * One signed-in page for the whole file: the login endpoint allows 10 attempts a minute per IP, and the refresh cookie
 * rotates, so a stored copy of it cannot be replayed by several browser contexts.
 */
let shared: BrowserContext | null = null;
let sharedPage: Page;

test.beforeAll(async ({ playwright, browser }, info) => {
  info.setTimeout(240_000);
  skipReason = await prepare(playwright, env.ARCHYRA_APP_URL as string);
  if (skipReason) return;
  shared = await browser.newContext({ baseURL: env.ARCHYRA_APP_URL, viewport: { width: 1440, height: 900 } });
  sharedPage = await shared.newPage();
  await signIn(sharedPage, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
});

test.afterAll(async ({ playwright }) => {
  await shared?.close();
  if (!startedHere || !analysisId || !token) return;
  const api = await playwright.request.newContext({ baseURL: env.ARCHYRA_APP_URL as string });
  await api.delete(`/api/v1/analyses/${analysisId}`, { headers: { Authorization: `Bearer ${token}` } }).catch(() => null);
  await api.dispose();
});

/** A test that runs on the shared signed-in page and skips, saying why, when the stack or the credentials are missing. */
function live(title: string, body: (page: Page) => Promise<void>) {
  test(title, async () => {
    test.skip(!!skipReason, skipReason ?? '');
    await body(sharedPage);
  });
}

const scopeRadio = (page: Page, name: 'Working' | 'Tests' | 'All') => page.getByRole('radiogroup', { name: /working code, tests, or all/i }).getByRole('radio', { name: new RegExp(`^${name}`) });
const counts = (page: Page) => page.getByText(/\d[\d,]* nodes?, \d[\d,]* relationships?/).first();
async function nodeTotal(page: Page): Promise<number> {
  const text = (await counts(page).textContent()) ?? '';
  return Number(/([\d,]+) nodes?/.exec(text)?.[1]?.replace(/,/g, ''));
}
/** The class view, explicitly: these tests are about scope and dragging, not about which view a bare URL opens on. */
const ARCH = () => `/analyses/${analysisId}/architecture?nodes=CLASS%2CINTERFACE`;
/** The Architecture URL with no `nodes` and no `edges`: whatever the default view of this repository is. */
const ARCH_BARE = () => `/analyses/${analysisId}/architecture`;
/** The files table keeps the previous rows while a new query loads; wait until it is idle before reading rows. */
const filesSettled = (page: Page) => expect(page.locator('table.table')).not.toHaveAttribute('aria-busy', 'true');

/** Flow coordinates of a node, read from its transform: independent of the zoom and of scrolling. */
async function flowPosition(node: Locator): Promise<{ x: number; y: number }> {
  const transform = await node.evaluate((el) => (el as HTMLElement).style.transform);
  const m = /translate\((-?[\d.]+)px,\s*(-?[\d.]+)px\)/.exec(transform);
  if (!m) throw new Error(`no translate in "${transform}"`);
  return { x: Number(m[1]), y: Number(m[2]) };
}

async function drag(page: Page, node: Locator, dx: number, dy: number) {
  const box = (await node.boundingBox()) as { x: number; y: number; width: number; height: number };
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx / 2, y + dy / 2, { steps: 6 });
  await page.mouse.move(x + dx, y + dy, { steps: 6 });
  await page.mouse.up();
}

/**
 * Two rendered nodes with room to move: the ones nearest the middle of the canvas. Off-screen nodes are not rendered
 * (onlyRenderVisibleElements), so a node on the edge could leave the page when dragged.
 */
async function pickNodes(page: Page): Promise<[Locator, Locator, string, string]> {
  const all = page.locator('.react-flow__node');
  await expect(all.first()).toBeVisible();
  const canvas = (await page.locator('.graph-canvas .react-flow').boundingBox()) as { x: number; y: number; width: number; height: number };
  const centre = { x: canvas.x + canvas.width / 2, y: canvas.y + canvas.height / 2 };
  const ranked = (
    await Promise.all(
      (await all.all()).map(async (node) => {
        const box = await node.boundingBox();
        const id = (await node.getAttribute('data-id')) as string;
        return { id, distance: box ? Math.hypot(box.x + box.width / 2 - centre.x, box.y + box.height / 2 - centre.y) : Infinity };
      }),
    )
  ).sort((m, n) => m.distance - n.distance);
  expect(ranked.length).toBeGreaterThan(1);
  const [first, second] = [ranked[0]!.id, ranked[1]!.id];
  const byId = (id: string) => page.locator(`.react-flow__node[data-id="${id}"]`);
  return [byId(first), byId(second), first, second];
}

// ---------------------------------------------------------------------------------------------------------------
// Scope: Architecture
// ---------------------------------------------------------------------------------------------------------------

live('Architecture: Working by default, three states, scope in the URL and gone again at the default', async (page) => {
  await page.goto(ARCH());
  await expect(page.locator('.react-flow__node').first()).toBeVisible();
  await expect(scopeRadio(page, 'Working')).toHaveAttribute('aria-checked', 'true');
  expect(page.url()).not.toContain('scope=');
  const working = await nodeTotal(page);

  await scopeRadio(page, 'Tests').click();
  await expect(page).toHaveURL(/scope=tests/);
  await expect(scopeRadio(page, 'Tests')).toHaveAttribute('aria-checked', 'true');
  await expect.poll(() => nodeTotal(page)).not.toBe(working);
  const tests = await nodeTotal(page);

  await scopeRadio(page, 'All').click();
  await expect(page).toHaveURL(/scope=all/);
  await expect.poll(() => nodeTotal(page)).toBe(working + tests); // the two views partition the nodes

  await scopeRadio(page, 'Working').click();
  await expect.poll(() => page.url()).not.toContain('scope=');
  await expect.poll(() => nodeTotal(page)).toBe(working);
});

live('Architecture: each option shows how many nodes it reveals, and the control is a keyboard radio group', async (page) => {
  await page.goto(ARCH());
  const group = page.getByRole('radiogroup', { name: /working code, tests, or all/i });
  await expect(group).toBeVisible();
  const label = (name: 'Working' | 'Tests' | 'All') => scopeRadio(page, name).textContent();
  const num = async (name: 'Working' | 'Tests' | 'All') => Number(((await label(name)) ?? '').replace(/\D/g, ''));
  expect((await num('Working')) + (await num('Tests'))).toBe(await num('All'));
  expect(await num('Tests')).toBeGreaterThan(0);

  await scopeRadio(page, 'Working').focus();
  await page.keyboard.press('ArrowRight');
  await expect(scopeRadio(page, 'Tests')).toHaveAttribute('aria-checked', 'true');
  await expect(scopeRadio(page, 'Tests')).toBeFocused();
  await expect(scopeRadio(page, 'Working')).toHaveAttribute('tabindex', '-1');
  await page.keyboard.press('ArrowLeft');
  await expect(scopeRadio(page, 'Working')).toHaveAttribute('aria-checked', 'true');
});

live('Architecture: an unknown scope in the URL falls back to Working', async (page) => {
  await page.goto(`${ARCH()}&scope=nonsense`);
  await expect(scopeRadio(page, 'Working')).toHaveAttribute('aria-checked', 'true');
});

// ---------------------------------------------------------------------------------------------------------------
// Scope: Files and Relationships
// ---------------------------------------------------------------------------------------------------------------

live('Files: All by default; Working and Tests send test=false and test=true and reset to the first page', async (page) => {
  await page.goto(`/analyses/${analysisId}/files?size=20&page=1`);
  await expect(page.locator('tbody tr').first()).toBeVisible();
  await expect(scopeRadio(page, 'All')).toHaveAttribute('aria-checked', 'true');
  expect(page.url()).not.toContain('scope=');

  const testsRequest = page.waitForRequest((r) => r.url().includes('/files?') && r.url().includes('test=true'));
  await scopeRadio(page, 'Tests').click();
  await testsRequest;
  await expect(page).toHaveURL(/scope=tests/);
  expect(page.url()).not.toContain('page=');
  await filesSettled(page);
  await expect(page.locator('tbody tr').first()).toBeVisible();
  const rows = page.locator('tbody tr');
  for (const row of await rows.all()) await expect(row.getByText('Test', { exact: true })).toBeVisible();

  const workingRequest = page.waitForRequest((r) => r.url().includes('/files?') && r.url().includes('test=false'));
  await scopeRadio(page, 'Working').click();
  await workingRequest;
  await expect(page).toHaveURL(/scope=working/);
  await filesSettled(page);
  await expect(page.locator('tbody tr').first()).toBeVisible();
  expect(await page.locator('tbody tr').getByText('Test', { exact: true }).count()).toBe(0);

  await scopeRadio(page, 'All').click();
  await expect.poll(() => page.url()).not.toContain('scope=');
});

live('Files: the total follows the server-side filter', async (page) => {
  await page.goto(`/analyses/${analysisId}/files?size=20`);
  const total = async () => Number(/of ([\d,]+)/.exec((await page.locator('nav[aria-label="File pages"]').textContent()) ?? '')?.[1]?.replace(/,/g, ''));
  await expect(page.locator('tbody tr').first()).toBeVisible();
  const all = await total();
  await scopeRadio(page, 'Tests').click();
  await expect.poll(total).toBeLessThan(all);
  const tests = await total();
  await scopeRadio(page, 'Working').click();
  await expect.poll(total).toBe(all - tests);
});

live('Relationships: Working by default; the list follows the scope and the detail panel does not', async (page) => {
  await page.goto(`/analyses/${analysisId}/relationships`);
  const list = page.locator('section[aria-label="Classes and interfaces"] li button');
  await expect(list.first()).toBeVisible();
  await expect(scopeRadio(page, 'Working')).toHaveAttribute('aria-checked', 'true');
  expect(page.url()).not.toContain('scope=');
  const working = await list.count();

  await scopeRadio(page, 'Tests').click();
  await expect(page).toHaveURL(/scope=tests/);
  await expect.poll(() => list.count()).not.toBe(working); // the list re-renders after the URL changes
  const tests = await list.count();
  expect(tests).toBeGreaterThan(0);
  await scopeRadio(page, 'All').click();
  await expect.poll(() => list.count()).toBe(working + tests);

  // Pick a test class, then go back to Working: it stays selected, with a notice and a Show all action.
  await scopeRadio(page, 'Tests').click();
  await list.first().click();
  await expect(page).toHaveURL(/item=/);
  await scopeRadio(page, 'Working').click();
  await expect(page).toHaveURL(/item=/);
  const notice = page.getByRole('status').filter({ hasText: /hidden from the list/ });
  await expect(notice).toBeVisible();
  await notice.getByRole('button', { name: 'Show all' }).click();
  await expect(page).toHaveURL(/scope=all/);
  await expect(notice).toHaveCount(0);
});

// ---------------------------------------------------------------------------------------------------------------
// Dragging
// ---------------------------------------------------------------------------------------------------------------

live('Dragging: a node moves and edges follow; a drag does not inspect, a click does', async (page) => {
  await page.goto(ARCH());
  const [a, , idA] = await pickNodes(page);
  const before = await flowPosition(a);

  await drag(page, a, 90, 70);
  const after = await flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`));
  expect(after.x).not.toBe(before.x);
  expect(after.y).not.toBe(before.y);
  expect(page.url()).not.toContain('node=');
  await expect(page.getByRole('complementary', { name: 'Node details' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Reset layout' })).toBeEnabled();

  await page.locator(`.react-flow__node[data-id="${idA}"]`).click();
  await expect(page).toHaveURL(/node=/);
  await expect(page.getByRole('complementary', { name: 'Node details' })).toBeVisible();
  // the click did not move it
  const clicked = await flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`));
  expect(clicked).toEqual(after);
});

live('Dragging: Reset layout is disabled until a node moves, then restores the automatic layout', async (page) => {
  await page.goto(ARCH());
  const [a, , idA] = await pickNodes(page);
  const reset = page.getByRole('button', { name: 'Reset layout' });
  await expect(reset).toBeDisabled();
  const before = await flowPosition(a);
  await drag(page, a, 120, 90);
  await expect(reset).toBeEnabled();
  await reset.click();
  await expect(reset).toBeDisabled();
  await page.getByRole('button', { name: 'Fit to screen' }).click();
  await expect.poll(() => flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`))).toEqual(before);
});

live('Dragging: a moved node keeps its place when a filter changes the layout; reload forgets it', async (page) => {
  await page.goto(ARCH());
  const [a, , idA] = await pickNodes(page);
  await drag(page, a, 100, 80);
  const placed = await flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`));

  // Toggle a relationship type: the layout is computed again for the new edge set.
  await page.getByRole('checkbox', { name: /^Extends/ }).click();
  await page.getByRole('button', { name: 'Fit to screen' }).click();
  await expect.poll(() => flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`))).toEqual(placed);
  await page.getByRole('checkbox', { name: /^Extends/ }).click(); // and back

  // Nothing is persisted: a reload brings the automatic layout back.
  await page.reload();
  await expect(page.locator('.react-flow__node').first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Reset layout' })).toBeDisabled();
});

live('Dragging: arrow keys nudge a selected node by 5px, 20px with Shift, and Enter still inspects', async (page) => {
  await page.goto(ARCH());
  const [a, , idA] = await pickNodes(page);
  const node = () => page.locator(`.react-flow__node[data-id="${idA}"]`);
  await a.focus();
  await page.keyboard.press('Enter'); // inspects and selects
  await expect(page).toHaveURL(/node=/);
  await expect(page.getByRole('complementary', { name: 'Node details' })).toBeVisible();
  await node().focus();

  const start = await flowPosition(node());
  await page.keyboard.press('ArrowRight');
  await expect.poll(() => flowPosition(node())).toEqual({ x: start.x + 5, y: start.y });
  await page.keyboard.press('Shift+ArrowDown');
  await expect.poll(() => flowPosition(node())).toEqual({ x: start.x + 5, y: start.y + 20 });
  await expect(page.getByRole('button', { name: 'Reset layout' })).toBeEnabled();
  // a polite, throttled message once the burst ends
  await expect(page.locator('.graph-canvas [role="status"][aria-live="polite"]')).toContainText(/Moved/);
});

live('Dragging: Ctrl or Cmd-click selects several nodes and one drag moves them all by the same amount', async (page) => {
  await page.goto(ARCH());
  const [a, b, idA, idB] = await pickNodes(page);
  await a.click({ modifiers: ['ControlOrMeta'] });
  await b.click({ modifiers: ['ControlOrMeta'] });
  expect(page.url()).not.toContain('node='); // editing the selection does not inspect
  const startA = await flowPosition(a);
  const startB = await flowPosition(b);

  await drag(page, a, 40, 30);
  const endA = await flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`));
  const endB = await flowPosition(page.locator(`.react-flow__node[data-id="${idB}"]`));
  expect(endA.x - startA.x).not.toBe(0);
  expect(endB.x - startB.x).toBeCloseTo(endA.x - startA.x, 5);
  expect(endB.y - startB.y).toBeCloseTo(endA.y - startA.y, 5);

  await page.keyboard.press('Escape');
  await expect(page.locator('.react-flow__node.selected')).toHaveCount(0);
});

live('Dragging: fullscreen keeps the moved positions', async (page) => {
  await page.goto(ARCH());
  const [a, , idA] = await pickNodes(page);
  await drag(page, a, 70, 50);
  const placed = await flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`));
  await page.getByRole('button', { name: 'Full screen' }).click().catch(() => null);
  await page.getByRole('button', { name: /Full screen|Exit full screen/ }).first().click().catch(() => null);
  await page.getByRole('button', { name: 'Fit to screen' }).click();
  await expect.poll(() => flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`))).toEqual(placed);
});

test('Dragging: one finger drags a node and the page does not scroll', async ({ browser }) => {
  test.skip(!!skipReason, skipReason ?? '');
  const context = await browser.newContext({ baseURL: env.ARCHYRA_APP_URL, viewport: { width: 820, height: 900 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  try {
    await signIn(page, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
    await page.goto(ARCH());
    const [a, , idA] = await pickNodes(page);
    const before = await flowPosition(a);
    const box = (await a.boundingBox()) as { x: number; y: number; width: number; height: number };
    const cdp = await context.newCDPSession(page);
    const x = box.x + box.width / 2;
    const y = box.y + box.height / 2;
    const scrollBefore = await page.evaluate(() => window.scrollY);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    for (let i = 1; i <= 10; i++) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + i * 6, y: y + i * 8 }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    const after = await flowPosition(page.locator(`.react-flow__node[data-id="${idA}"]`));
    expect(after.x).not.toBe(before.x);
    expect(await page.evaluate(() => window.scrollY)).toBe(scrollBefore);
    expect(page.url()).not.toContain('node=');
  } finally {
    await context.close();
  }
});

// ---------------------------------------------------------------------------------------------------------------
// The module default (spec/graph-default-and-name-filter, Task A)
// ---------------------------------------------------------------------------------------------------------------

type ApiGraph = { nodes: { id: string; type: string; name: string; test: boolean }[]; edges: { source: string; target: string; type: string }[] };

async function readGraph(page: Page): Promise<ApiGraph> {
  const res = await page.request.get(`/api/v1/analyses/${analysisId}/graph`, { headers: { Authorization: `Bearer ${token}` } });
  expect(res.status()).toBe(200);
  return (await res.json()) as ApiGraph;
}

/** What the module default must draw for this graph: working modules, and the DEPENDS_ON edges between them. */
function expectedModuleView(graph: ApiGraph) {
  const modules = graph.nodes.filter((n) => n.type === 'MODULE' && !n.test && !n.id.startsWith('external:'));
  const ids = new Set(modules.map((n) => n.id));
  const edges = new Set(graph.edges.filter((e) => e.type === 'DEPENDS_ON' && ids.has(e.source) && ids.has(e.target)).map((e) => `${e.source}|${e.target}`));
  return { modules, edges: edges.size, applies: modules.length >= 2 && edges.size >= 1 };
}

const nodeLabels = (page: Page) => page.locator('.react-flow__node').evaluateAll((els) => els.map((el) => el.getAttribute('aria-label') ?? ''));
const relationshipTotal = async (page: Page) => Number(/([\d,]+) relationships?/.exec((await counts(page).textContent()) ?? '')?.[1]?.replace(/,/g, ''));
const checkbox = (page: Page, name: RegExp) => page.getByRole('checkbox', { name });

live('Architecture: a fresh URL opens on modules joined by DEPENDS_ON only, the chips say so, and Classes switches to the class view', async (page) => {
  const view = expectedModuleView(await readGraph(page));
  test.skip(!view.applies, 'the analysis has fewer than 2 working modules joined by a DEPENDS_ON edge, so the module default does not apply');

  await page.goto(ARCH_BARE());
  await expect(page.locator('.react-flow__node').first()).toBeVisible();
  expect(page.url()).not.toMatch(/[?&](nodes|edges)=/);
  const checkedLabels = (group: string) =>
    page.getByRole('group', { name: group }).getByRole('checkbox').evaluateAll((els) => els.filter((el) => (el as HTMLInputElement).checked).map((el) => el.closest('label')?.textContent?.trim() ?? ''));
  expect(await checkedLabels('Relationship types')).toEqual([expect.stringMatching(/^Depends on/)]); // DEPENDS_ON only: no inheritance edges
  expect(await checkedLabels('Node types')).toEqual([expect.stringMatching(/^Modules/)]);
  const caption = page.getByTestId('graph-default-caption');
  await expect(caption).toContainText('Modules joined by DEPENDS_ON');
  expect(await nodeTotal(page)).toBe(view.modules.length);
  expect(await relationshipTotal(page)).toBe(view.edges);
  expect((await nodeLabels(page)).every((label) => /, module$/.test(label))).toBe(true);

  // the Overview preview shows the same kind of set
  await page.goto(`/analyses/${analysisId}/overview`);
  const preview = page.getByRole('application', { name: /Architecture preview/ }).or(page.locator('[aria-label="Architecture preview (not interactive)"]'));
  await expect(preview.first()).toBeVisible();
  await expect(page.locator('.react-flow__node').first()).toBeVisible();
  const previewLabels = await nodeLabels(page);
  expect(previewLabels.length).toBeGreaterThan(0);
  expect(previewLabels.every((label) => /, module$/.test(label))).toBe(true);
  await expect(page.getByText(/modules, joined by DEPENDS_ON/)).toBeVisible();

  // choosing Classes leaves the module default: the caption goes, the selection is explicit, classes appear
  await page.goto(ARCH_BARE());
  await expect(caption).toBeVisible();
  await checkbox(page, /^Classes/).click();
  await expect(caption).toHaveCount(0);
  await expect(page).toHaveURL(/nodes=/);
  await expect.poll(async () => (await nodeLabels(page)).some((label) => /, class$/.test(label))).toBe(true);
  await expect.poll(() => nodeTotal(page)).toBeGreaterThan(view.modules.length);
});

live('Architecture: an explicit nodes parameter keeps the classic edges, so old links draw what they always did', async (page) => {
  await page.goto(`${ARCH_BARE()}?nodes=CLASS%2CINTERFACE`);
  await expect(page.locator('.react-flow__node').first()).toBeVisible();
  await expect(checkbox(page, /^Depends on/)).toBeChecked();
  await expect(checkbox(page, /^Extends/)).toBeChecked();
  await expect(checkbox(page, /^Implements/)).toBeChecked();
  await expect(page.getByTestId('graph-default-caption')).toHaveCount(0);
});
