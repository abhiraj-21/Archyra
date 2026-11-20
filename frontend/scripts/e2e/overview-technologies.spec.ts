import { expect, test, type BrowserContext, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { env, signIn } from './helpers';

/**
 * The Technologies section of the repository Overview against the REAL backend (no mocks, no interception). It reuses
 * COMPLETED analyses of the test account and never starts one: one that has detected technologies, and one that has none
 * (that test skips, saying so, when every analysis of the account has some). The expected chips are read from the real
 * `GET /analyses/{id}/repository` response through the API. Everything skips, saying why, when the stack, the credentials
 * or a suitable analysis are missing.
 */
test.describe.configure({ mode: 'serial' });

interface Technology {
  name: string;
  category: string;
}

let skipReason: string | null = null;
let withTechnologies: { id: string; technologies: Technology[] } | null = null;
let withoutTechnologies: string | null = null;

async function prepare(playwright: PlaywrightWorkerArgs['playwright'], baseURL: string): Promise<string | null> {
  const missing = ['ARCHYRA_TEST_EMAIL', 'ARCHYRA_TEST_PASSWORD'].filter((v) => !env[v]);
  if (missing.length) return `set ${missing.join(', ')} in .env.local`;
  const api = await playwright.request.newContext({ baseURL });
  try {
    const csrf = await api.get('/api/v1/auth/csrf').catch(() => null);
    if (!csrf || csrf.status() !== 200) return `backend not reachable through ${baseURL}`;
    const login = await api.post('/api/v1/auth/login', { data: { email: env.ARCHYRA_TEST_EMAIL, password: env.ARCHYRA_TEST_PASSWORD } });
    if (login.status() !== 200) return 'the test account cannot sign in';
    const headers = { Authorization: `Bearer ${((await login.json()) as { accessToken: string }).accessToken}` };
    const list = (await (await api.get('/api/v1/analyses?page=0&size=50', { headers })).json()) as { items: { analysisId: string; status: string }[] };
    const completed = list.items.filter((i) => i.status === 'COMPLETED');
    if (completed.length === 0) return 'the test account has no completed analysis (analyse a repository first)';
    for (const item of completed) {
      const res = await api.get(`/api/v1/analyses/${item.analysisId}/repository`, { headers });
      if (res.status() !== 200) continue;
      const body = (await res.json()) as { technologies?: Technology[] };
      if (!Array.isArray(body.technologies)) return 'the backend does not return `technologies` yet (restart it with this change)';
      if (body.technologies.length > 0) withTechnologies ??= { id: item.analysisId, technologies: body.technologies };
      else withoutTechnologies ??= item.analysisId;
    }
    return withTechnologies || withoutTechnologies ? null : 'no completed analysis could be read';
  } finally {
    await api.dispose();
  }
}

let shared: BrowserContext | null = null;
let page: Page;

test.beforeAll(async ({ playwright, browser }, info) => {
  info.setTimeout(120_000);
  skipReason = await prepare(playwright, env.ARCHYRA_APP_URL as string);
  if (skipReason) return;
  shared = await browser.newContext({ baseURL: env.ARCHYRA_APP_URL, viewport: { width: 1440, height: 900 } });
  page = await shared.newPage();
  await signIn(page, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
});
test.afterAll(async () => {
  await shared?.close();
});
test.beforeEach(() => {
  test.skip(skipReason !== null, skipReason ?? '');
});

const section = () => page.locator('section[aria-labelledby="technologies-heading"]');
const NOTE = 'Detected from manifests, files and imports. Best effort, it can miss things.';

test('the Overview lists the technologies the repository endpoint returns', async () => {
  test.skip(!withTechnologies, 'no completed analysis of the test account has detected technologies');
  const { id, technologies } = withTechnologies!;
  await page.goto(`/analyses/${id}/overview`);

  await expect(page.getByRole('heading', { level: 2, name: 'Technologies' })).toBeVisible();
  await expect(section().getByText(NOTE)).toBeVisible();

  // Open every group that is cut at 12, then every name the API returned is a chip, and nothing else is.
  for (const button of await section().getByRole('button', { name: /^Show \d+ more$/ }).all()) {
    await button.click();
    await expect(button).toHaveAttribute('aria-expanded', 'true');
  }
  const expected = [...new Set(technologies.map((t) => t.name))].sort();
  const shown = (await section().getByRole('listitem').allTextContents()).map((t) => t.trim());
  // A name that two categories share (it would be listed under both) counts once per group; compare as sets.
  expect([...new Set(shown)].sort()).toEqual(expected);

  // Each category the API returned has a heading.
  const headings = (await section().getByRole('heading', { level: 3 }).allTextContents()).map((t) => t.trim());
  expect(headings.length).toBeGreaterThan(0);
  expect(headings.length).toBeLessThanOrEqual(new Set(technologies.map((t) => t.category)).size);

  // The summary tile beside Languages.
  const tile = page.locator('.stat', { hasText: 'Technologies' });
  await expect(tile).toContainText('detected');
  await expect(tile.locator('.num')).toHaveText(String(new Set(technologies.map((t) => `${t.category}|${t.name}`)).size));
});

test('a group of more than 12 shows the first 12 and a keyboard-operable "Show N more" button', async () => {
  test.skip(!withTechnologies, 'no completed analysis of the test account has detected technologies');
  const { id, technologies } = withTechnologies!;
  const perCategory = new Map<string, Set<string>>();
  for (const t of technologies) perCategory.set(t.category, (perCategory.get(t.category) ?? new Set()).add(t.name));
  const big = [...perCategory.values()].find((names) => names.size > 12);
  test.skip(!big, 'no group of this repository has more than 12 technologies');
  await page.goto(`/analyses/${id}/overview`);

  const button = section().getByRole('button', { name: `Show ${big!.size - 12} more` }).first();
  await expect(button).toHaveAttribute('aria-expanded', 'false');
  await button.focus();
  await page.keyboard.press('Enter');
  await expect(section().getByRole('button', { name: 'Show fewer' }).first()).toHaveAttribute('aria-expanded', 'true');
});

test('a repository with no detected technologies says so', async () => {
  test.skip(!withoutTechnologies, 'every completed analysis of the test account has detected technologies');
  await page.goto(`/analyses/${withoutTechnologies}/overview`);

  await expect(page.getByRole('heading', { level: 2, name: 'Technologies' })).toBeVisible();
  await expect(section().getByText('No frameworks, libraries or tools were detected.')).toBeVisible();
  await expect(section().getByRole('listitem')).toHaveCount(0);
  await expect(page.locator('.stat', { hasText: 'Technologies' }).locator('.num')).toHaveText('0');
});
