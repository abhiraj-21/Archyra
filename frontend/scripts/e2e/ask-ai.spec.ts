import { expect, test, type BrowserContext, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { env, signIn } from './helpers';

/**
 * The Ask AI tab against the REAL backend (no mocks). It reuses a COMPLETED analysis of the test account and never starts
 * one. Nothing here spends an AI question: failures are produced by intercepting the chat request and answering it with
 * the backend's own error bodies (route interception only injects network faults, never data). The one test that asks a
 * real question runs only with ARCHYRA_E2E_AI=1, because it spends real provider quota and one AI_QUERY unit.
 * Everything skips, saying why, when the stack, the credentials or a suitable analysis are missing.
 */
test.describe.configure({ mode: 'serial' });

type IndexStatus = 'NOT_INDEXED' | 'INDEXING' | 'READY' | 'FAILED' | 'EMPTY';
let skipReason: string | null = null;
let analysisId = '';
let indexStatus: IndexStatus = 'NOT_INDEXED';
let indexRetryable = false;

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
    let fallback: { id: string; status: IndexStatus; retryable: boolean } | null = null;
    for (const item of list.items.filter((i) => i.status === 'COMPLETED')) {
      const res = await api.get(`/api/v1/analyses/${item.analysisId}/ai-index`, { headers });
      if (res.status() !== 200) continue;
      const index = (await res.json()) as { status: IndexStatus; retryable: boolean };
      if (index.status === 'READY') {
        analysisId = item.analysisId;
        indexStatus = 'READY';
        return null;
      }
      fallback ??= { id: item.analysisId, status: index.status, retryable: index.retryable };
    }
    if (!fallback) return 'the test account has no completed analysis (analyse a repository first)';
    analysisId = fallback.id;
    indexStatus = fallback.status;
    indexRetryable = fallback.retryable;
    return null;
  } finally {
    await api.dispose();
  }
}

let shared: BrowserContext | null = null;
let sharedPage: Page;

test.beforeAll(async ({ playwright, browser }, info) => {
  info.setTimeout(120_000);
  skipReason = await prepare(playwright, env.ARCHYRA_APP_URL as string);
  if (skipReason) return;
  shared = await browser.newContext({ baseURL: env.ARCHYRA_APP_URL, viewport: { width: 1440, height: 900 } });
  sharedPage = await shared.newPage();
  await signIn(sharedPage, env.ARCHYRA_TEST_EMAIL as string, env.ARCHYRA_TEST_PASSWORD as string);
});

test.afterAll(async () => {
  await shared?.close();
});

function live(title: string, body: (page: Page) => Promise<void>, needs: 'any' | 'ready' = 'any') {
  test(title, async () => {
    test.skip(!!skipReason, skipReason ?? '');
    test.skip(needs === 'ready' && indexStatus !== 'READY', `the account's analysis is ${indexStatus}, not READY`);
    await body(sharedPage);
  });
}

const ASK = () => `/analyses/${analysisId}/ask-ai`;
const composer = (page: Page) => page.getByLabel('Your question');
const askButton = (page: Page) => page.getByRole('button', { name: 'Ask', exact: true });

/** Answers the next chat request with a backend error body, so the failure UI can be seen without spending a question. */
async function failChat(page: Page, status: number, code: string, headers: Record<string, string> = {}) {
  let hits = 0;
  await page.route('**/api/v1/analyses/*/chat', async (route) => {
    hits += 1;
    await route.fulfill({ status, headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify({ code, message: 'injected by the e2e test' }) });
  });
  return () => hits;
}

live('shows the panel for the index state the analysis is in', async (page) => {
  await page.goto(ASK());
  switch (indexStatus) {
    case 'NOT_INDEXED':
      await expect(page.getByRole('heading', { name: /isn't enabled for this analysis yet/i })).toBeVisible();
      await expect(page.getByRole('textbox', { name: 'Your question' })).toHaveCount(0); // no chat UI
      await page.getByRole('button', { name: 'Re-analyze' }).first().click();
      await expect(page.getByText(/Ask AI conversations for the current analysis are deleted when the new analysis completes/i)).toBeVisible();
      await page.keyboard.press('Escape');
      break;
    case 'INDEXING':
      await expect(page.getByRole('heading', { name: /Preparing Ask AI/i })).toBeVisible();
      await expect(composer(page)).toBeDisabled();
      break;
    case 'FAILED':
      await expect(page.getByRole('heading', { name: /could not be prepared/i })).toBeVisible();
      await expect(indexRetryable ? page.getByRole('button', { name: 'Retry' }) : page.getByRole('button', { name: 'Re-analyze' }).first()).toBeVisible();
      break;
    case 'EMPTY':
      await expect(page.getByText(/no non-test code to ask about/i)).toBeVisible();
      break;
    case 'READY':
      await expect(page.getByRole('navigation', { name: 'Conversations' })).toBeVisible();
      await expect(composer(page)).toBeEnabled();
      await expect(page.getByText(/can be wrong/i)).toBeVisible();
      await expect(page.getByText(/Gemini or Groq/i)).toBeVisible();
      break;
  }
  await expect(page.getByText(/coming soon/i)).toHaveCount(0);
});

live('creates a conversation, keeps it in ?c= across a reload, and deletes it', async (page) => {
  await page.goto(ASK());
  const before = await page.getByRole('navigation', { name: 'Conversations' }).getByRole('listitem').count();
  await page.getByRole('button', { name: 'New conversation' }).click();
  await expect(page).toHaveURL(/[?&]c=[0-9a-f-]{36}/);
  const id = new URL(page.url()).searchParams.get('c') as string;
  const list = page.getByRole('navigation', { name: 'Conversations' }).getByRole('listitem');
  await expect(list).toHaveCount(before + 1);

  await page.reload();
  await expect(page).toHaveURL(new RegExp(`[?&]c=${id}`));
  await expect(page.getByRole('button', { name: /New conversation/ }).first()).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Ask about this repository' })).toBeVisible();

  await page.getByRole('button', { name: /Delete conversation New conversation/ }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click();
  await expect(list).toHaveCount(before);
  await expect(page).not.toHaveURL(new RegExp(`[?&]c=${id}`));
}, 'ready');

live('an unknown ?c= falls back to the empty state', async (page) => {
  await page.goto(`${ASK()}?c=00000000-0000-4000-8000-000000000000`);
  await expect(page.getByText(/That conversation no longer exists/i)).toBeVisible();
  await expect(page).not.toHaveURL(/[?&]c=/);
  await expect(composer(page)).toBeEnabled();
}, 'ready');

live('an example question fills the composer and sends nothing', async (page) => {
  let chats = 0;
  await page.route('**/api/v1/analyses/*/chat', async (route) => {
    chats += 1;
    await route.abort();
  });
  await page.goto(ASK());
  await page.getByRole('button', { name: 'How does authentication work?' }).click();
  await expect(composer(page)).toHaveValue('How does authentication work?');
  await expect(composer(page)).toBeFocused();
  expect(chats).toBe(0);
  await page.unroute('**/api/v1/analyses/*/chat');
}, 'ready');

live('USAGE_LIMIT_EXCEEDED replaces the composer with the limit panel', async (page) => {
  const hits = await failChat(page, 403, 'USAGE_LIMIT_EXCEEDED');
  await page.goto(ASK());
  await composer(page).fill('A question that is refused');
  await askButton(page).click();
  await expect(page.getByRole('heading', { name: /used all your free questions/i })).toBeVisible();
  await expect(composer(page)).toHaveCount(0);
  await page.getByRole('button', { name: 'See Premium' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  expect(hits()).toBe(1);
  await page.unroute('**/api/v1/analyses/*/chat');
}, 'ready');

live('RATE_LIMITED shows a countdown and keeps the text', async (page) => {
  await failChat(page, 429, 'RATE_LIMITED', { 'retry-after': '3' });
  await page.goto(ASK());
  await composer(page).fill('Asked too fast');
  await askButton(page).click();
  await expect(page.getByText(/Too many questions/i).first()).toBeVisible();
  await expect(page.getByText(/ask again in/i)).toBeVisible();
  await expect(composer(page)).toHaveValue('Asked too fast');
  await expect(composer(page)).toBeDisabled();
  await expect(composer(page)).toBeEnabled({ timeout: 10_000 }); // the countdown ended
  await expect(composer(page)).toHaveValue('Asked too fast');
  await page.unroute('**/api/v1/analyses/*/chat');
}, 'ready');

live('AI_UNAVAILABLE says the question was not counted, offers Try again and keeps the text', async (page) => {
  const hits = await failChat(page, 503, 'AI_UNAVAILABLE');
  await page.goto(ASK());
  await composer(page).fill('Nobody is home');
  await askButton(page).click();
  await expect(page.getByText(/wasn.t counted/i)).toBeVisible();
  await expect(composer(page)).toHaveValue('Nobody is home');
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect.poll(hits).toBe(2);
  await page.unroute('**/api/v1/analyses/*/chat');
}, 'ready');

live('AI_NOT_READY refreshes the index state and keeps the text', async (page) => {
  await failChat(page, 409, 'AI_NOT_READY');
  await page.goto(ASK());
  const indexRequest = page.waitForRequest((request) => request.url().endsWith('/ai-index') && request.method() === 'GET');
  await composer(page).fill('Too early');
  await askButton(page).click();
  await indexRequest;
  await expect(page.getByText(/Ask AI is not ready/i)).toBeVisible();
  await expect(composer(page)).toHaveValue('Too early');
  await page.unroute('**/api/v1/analyses/*/chat');
}, 'ready');

test.describe('a real question (spends provider quota and one AI_QUERY unit)', () => {
  test.skip(process.env.ARCHYRA_E2E_AI !== '1', 'set ARCHYRA_E2E_AI=1 to ask a real question');

  live('gets a real answer with sources, expands one and opens it in the graph', async (page) => {
    await page.goto(ASK());
    await composer(page).fill('What are the main classes of this repository and what do they do?');
    await askButton(page).click();
    const answer = page.getByRole('article', { name: 'Answer' }).last();
    await expect(answer).toBeVisible({ timeout: 90_000 });
    await expect(page.getByText(/Answered by (Gemini|Groq)/)).toBeVisible();
    const chips = answer.getByRole('button', { name: /^Source \d+:/ });
    if ((await chips.count()) === 0) {
      await expect(answer.getByText('No sources cited')).toBeVisible();
      return;
    }
    await chips.first().click();
    await expect(chips.first()).toHaveAttribute('aria-expanded', 'true');
    await expect(answer.getByRole('region').first()).toContainText(/\.\w+/); // a file name
    const link = answer.getByRole('link', { name: /Open in graph/ }).first();
    if (await link.count()) {
      await link.click();
      await expect(page).toHaveURL(/\/architecture\?.*node=/);
    }
  }, 'ready');
});
