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
let token = '';
/** A conversation of the account with at least one message (the guide is a collapsible panel there), or null. */
let busyConversation: string | null = null;

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
    const list = (await (await api.get('/api/v1/analyses?page=0&size=50', { headers })).json()) as { items: { analysisId: string; status: string }[] };
    let fallback: { id: string; status: IndexStatus; retryable: boolean } | null = null;
    for (const item of list.items.filter((i) => i.status === 'COMPLETED')) {
      const res = await api.get(`/api/v1/analyses/${item.analysisId}/ai-index`, { headers });
      if (res.status() !== 200) continue;
      const index = (await res.json()) as { status: IndexStatus; retryable: boolean };
      if (index.status === 'READY') {
        analysisId = item.analysisId;
        indexStatus = 'READY';
        const conversations = await api.get(`/api/v1/analyses/${item.analysisId}/conversations?page=0&size=50`, { headers });
        if (conversations.status() === 200) {
          busyConversation = ((await conversations.json()) as { items: { id: string; messageCount: number }[] }).items.find((c) => c.messageCount > 0)?.id ?? null;
        }
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
      await expect(page.getByText(/third-party free-tier AI providers/i)).toBeVisible();
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
  await expect(page.getByRole('heading', { name: 'Questions that work well' })).toBeVisible();

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

/** The repository's own names for the examples, derived the way the guide derives them, from the graph the API serves. */
async function ownNames(page: Page): Promise<{ className: string; moduleName: string }> {
  const res = await page.request.get(`/api/v1/analyses/${analysisId}/graph`, { headers: { Authorization: `Bearer ${token}` } });
  expect(res.status()).toBe(200);
  const graph = (await res.json()) as { nodes: { id: string; type: string; name: string; test: boolean }[]; edges: { source: string; target: string; type: string }[] };
  const classes = new Map(graph.nodes.filter((n) => (n.type === 'CLASS' || n.type === 'INTERFACE') && !n.test && !n.id.startsWith('external:')).map((n) => [n.id, n]));
  const dependents = new Map<string, Set<string>>();
  for (const e of graph.edges) {
    if (e.type !== 'DEPENDS_ON' || e.source === e.target || !classes.has(e.source) || !classes.has(e.target)) continue;
    dependents.set(e.target, (dependents.get(e.target) ?? new Set()).add(e.source));
  }
  const className = [...dependents.entries()].map(([id, set]) => ({ name: classes.get(id)?.name ?? '', count: set.size })).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))[0]?.name;
  const files = new Set(graph.nodes.filter((n) => n.type === 'FILE' && !n.test).map((n) => n.id));
  const modules = new Map(graph.nodes.filter((n) => n.type === 'MODULE' && !n.test).map((n) => [n.id, n.name]));
  const sizes = new Map<string, number>();
  for (const e of graph.edges) if (e.type === 'CONTAINS' && modules.has(e.source) && files.has(e.target)) sizes.set(e.source, (sizes.get(e.source) ?? 0) + 1);
  const moduleName = [...sizes.entries()].map(([id, count]) => ({ name: modules.get(id) ?? '', count })).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))[0]?.name;
  test.skip(!className || !moduleName, 'the graph has no class dependencies or no module to name');
  return { className: className as string, moduleName: moduleName as string };
}

live('the guide shows what to ask and what not to, with this repository\'s own class and module names, and an honest counting note', async (page) => {
  const { className, moduleName } = await ownNames(page);
  await page.goto(ASK());
  await page.getByRole('button', { name: 'New conversation' }).click();
  await expect(page).toHaveURL(/[?&]c=[0-9a-f-]{36}/);

  await expect(page.getByRole('heading', { name: 'Questions that work well' })).toBeVisible();
  for (const group of ['The project', 'How things work', 'Relationships', 'Explain code']) await expect(page.getByRole('heading', { name: group })).toBeVisible();
  await expect(page.getByRole('button', { name: `What classes depend on \`${className}\`?` })).toBeVisible();
  await expect(page.getByRole('button', { name: `Explain \`${className}\`` })).toBeVisible();
  await expect(page.getByRole('button', { name: `What is the role of the \`${moduleName}\` module?` })).toBeVisible();
  await expect(page.getByRole('heading', { name: /won.t work, or will waste one of your questions/ })).toBeVisible();
  for (const text of ['Not about this repository', 'Asking the AI to change code', 'How code is tested', 'commit history', 'Swift', 'Each conversation is separate']) {
    await expect(page.getByText(text, { exact: false }).first()).toBeVisible();
  }
  // the counting note beside the composer states the rule; the count itself is the server's number
  await expect(page.getByTestId('counting-note')).toContainText("isn't counted");
  await expect(page.getByTestId('counting-note')).toContainText('even if the answer is "I can\'t tell"');
  await expect(page.getByRole('status').filter({ hasText: /questions? left|Unlimited questions|No questions left/ })).toBeVisible();

  // an example fills the box and sends nothing
  let chats = 0;
  await page.route('**/api/v1/analyses/*/chat', async (route) => {
    chats += 1;
    await route.abort();
  });
  await page.getByRole('button', { name: `Explain \`${className}\`` }).click();
  await expect(composer(page)).toHaveValue(`Explain \`${className}\``);
  expect(chats).toBe(0);
  await page.unroute('**/api/v1/analyses/*/chat');

  await page.getByRole('button', { name: /Delete conversation New conversation/ }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click();
}, 'ready');

live('in a conversation with messages the guide is a "What to ask" panel that opens and closes from the keyboard', async (page) => {
  test.skip(!busyConversation, 'the account has no conversation with messages (asking one spends a question: see ARCHYRA_E2E_AI)');
  await page.goto(`${ASK()}?c=${busyConversation}`);
  const toggle = page.getByRole('button', { name: 'What to ask' });
  await expect(toggle).toBeVisible();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('heading', { name: 'Questions that work well' })).toHaveCount(0);

  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByRole('heading', { name: 'Questions that work well' })).toBeVisible();
  await page.keyboard.press('Space');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('heading', { name: 'Questions that work well' })).toHaveCount(0);
}, 'ready');

live('QUESTION_NOT_ANSWERABLE says the question was not counted, keeps the text, opens the guide and offers no Try again', async (page) => {
  const hits = await failChat(page, 422, 'QUESTION_NOT_ANSWERABLE');
  await page.goto(ASK());
  await composer(page).fill('Write me a poem about autumn');
  await askButton(page).click();

  const notice = page.getByRole('alert').or(page.getByRole('status')).filter({ hasText: 'was not sent' });
  await expect(notice).toContainText('Not counted');
  await expect(notice).toContainText('Write me a poem about autumn');
  await expect(composer(page)).toHaveValue('Write me a poem about autumn'); // kept, to be edited
  await expect(page.getByRole('button', { name: 'Try again' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Questions that work well' })).toBeVisible();
  expect(hits()).toBe(1);
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
    await composer(page).fill('What is this project about?');
    await askButton(page).click();
    const answer = page.getByRole('article', { name: 'Answer' }).last();
    await expect(answer).toBeVisible({ timeout: 90_000 });
    await expect(page.getByText(/Answered by \S+/)).toBeVisible();
    // an overview answer is built from the README and the project facts, so it cites at least one source (the repository node, a module or a class)
    const chips = answer.getByRole('button', { name: /^Source \d+:/ });
    await expect(chips.first()).toBeVisible();
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
