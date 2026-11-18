#!/usr/bin/env node
// Prints the SHAPE of every live API response the frontend types (src/api/types.ts) describe: key names, JSON types,
// which keys are absent, and the values of enum-like fields. Never prints emails, names, ids, URLs or tokens.
//
//   node scripts/e2e/probe-api.mjs            # uses ARCHYRA_TEST_* (and ARCHYRA_ADMIN_* if set) from .env.local
//   node scripts/e2e/probe-api.mjs --create   # also starts an analysis of ARCHYRA_CAPTURE_REPO if none is COMPLETED
import { loadEnv } from '../lib/env.mjs';

const env = loadEnv();
const API = `${env.VITE_BACKEND_ORIGIN || 'http://localhost:8080'}/api/v1`;
const ENUM_KEYS = new Set(['status', 'type', 'role', 'plan', 'usageType', 'kind', 'tokenType', 'code', 'headerName', 'linkedProviders', 'external', 'emailVerified', 'enabled', 'locked', 'limitReached']);

function shape(value, key = '') {
  if (value === null) return 'null';
  if (Array.isArray(value)) {
    if (!value.length) return '[] (empty)';
    if (ENUM_KEYS.has(key)) return `[${[...new Set(value)].join(', ')}]`;
    const merged = {};
    for (const item of value.slice(0, 50)) {
      if (item && typeof item === 'object' && !Array.isArray(item)) {
        for (const [k, v] of Object.entries(item)) {
          const s = JSON.stringify(shape(v, k));
          (merged[k] ??= new Set()).add(s);
        }
      } else return [`${typeof item}`, `(${value.length} items)`];
    }
    const sample = value.slice(0, 50);
    const out = {};
    for (const [k, set] of Object.entries(merged)) {
      const present = sample.filter((i) => i && Object.hasOwn(i, k)).length;
      out[k + (present < sample.length ? '?' : '')] = [...set].map((s) => JSON.parse(s)).slice(0, 4);
    }
    return { [`items (${value.length})`]: out };
  }
  if (typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      if (['outgoing', 'incoming'].includes(k) && v && typeof v === 'object') out[k] = `{ ${Object.keys(v).join(', ')} } -> numbers`;
      else out[k] = shape(v, k);
    }
    return out;
  }
  if (ENUM_KEYS.has(key)) return `${typeof value} = ${JSON.stringify(value)}`;
  return typeof value;
}

async function call(path, { token, method = 'GET', body, headers = {} } = {}) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : undefined; } catch { json = text.slice(0, 80); }
  return { status: res.status, json, headers: res.headers };
}

function report(label, { status, json }) {
  console.log(`\n## ${label} -> ${status}`);
  console.log(JSON.stringify(shape(json), null, 2));
}

async function login(email, password, who) {
  const res = await call('/auth/login', { method: 'POST', body: { email, password } });
  if (res.status !== 200) {
    console.error(`${who} login failed: ${res.status} ${res.json?.code ?? ''}`);
    process.exit(1);
  }
  report(`POST /auth/login (${who})`, res);
  return res.json.accessToken;
}

const enc = (id) => encodeURIComponent(id).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);

async function main() {
  if (!env.ARCHYRA_TEST_EMAIL || !env.ARCHYRA_TEST_PASSWORD) {
    console.error('Set ARCHYRA_TEST_EMAIL and ARCHYRA_TEST_PASSWORD in frontend/.env.local');
    process.exit(2);
  }
  report('GET /auth/csrf', await call('/auth/csrf'));
  report('POST /auth/refresh without CSRF', await call('/auth/refresh', { method: 'POST' }));
  report('GET /users/me without token', await call('/users/me'));

  const token = await login(env.ARCHYRA_TEST_EMAIL, env.ARCHYRA_TEST_PASSWORD, 'test user');
  report('GET /users/me', await call('/users/me', { token }));
  report('GET /auth/me', await call('/auth/me', { token }));
  report('GET /usage', await call('/usage', { token }));
  report('GET /admin/stats as USER', await call('/admin/stats', { token }));
  report('GET /analyses/{random}', await call('/analyses/00000000-0000-0000-0000-000000000000', { token }));
  report('GET /analyses/not-a-uuid', await call('/analyses/not-a-uuid', { token }));
  report('POST /analyses invalid URL', await call('/analyses', { token, method: 'POST', body: { repositoryUrl: 'https://gitlab.com/a/b' } }));

  const list = await call('/analyses?page=0&size=20', { token });
  report('GET /analyses', list);
  let done = list.json?.items?.find((i) => i.status === 'COMPLETED');
  if (!done && process.argv.includes('--create')) {
    const created = await call('/analyses', { token, method: 'POST', body: { repositoryUrl: env.ARCHYRA_CAPTURE_REPO } });
    report('POST /analyses', created);
    const id = created.json?.analysisId;
    for (let i = 0; i < 180 && id; i++) {
      const s = await call(`/analyses/${id}`, { token });
      if (i === 0) report('GET /analyses/{id} (running)', s);
      if (s.json?.status === 'COMPLETED' || s.json?.status === 'FAILED') {
        report('GET /analyses/{id} (terminal)', s);
        if (s.json.status === 'COMPLETED') done = { analysisId: id };
        break;
      }
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  if (!done) {
    console.log('\nNo COMPLETED analysis for this user: rerun with --create to probe the read endpoints.');
  } else {
    const a = `/analyses/${done.analysisId}`;
    report('GET /analyses/{id}', await call(a, { token }));
    report('GET /repository', await call(`${a}/repository`, { token }));
    const graph = await call(`${a}/graph`, { token });
    report('GET /graph', graph);
    report('GET /graph?nodeType=CLASS&relationshipType=DEPENDS_ON', await call(`${a}/graph?nodeType=CLASS&relationshipType=DEPENDS_ON`, { token }));
    report('GET /graph?nodeType=BAD', await call(`${a}/graph?nodeType=BAD`, { token }));
    report('GET /graph?level=BEGINNER', await call(`${a}/graph?level=BEGINNER`, { token }));
    report('GET /graph?level=EXPERT', await call(`${a}/graph?level=EXPERT`, { token }));
    report('GET /graph?depth=2', await call(`${a}/graph?depth=2`, { token }));
    const nodes = graph.json?.nodes ?? [];
    const byType = (t) => nodes.find((n) => n.type === t);
    for (const t of ['CLASS', 'INTERFACE', 'METHOD', 'FILE', 'MODULE', 'REPOSITORY', 'FUNCTION']) {
      const n = byType(t);
      if (n) report(`GET /nodes/{${t}}`, await call(`${a}/nodes/${enc(n.id)}`, { token }));
    }
    report('GET /nodes/{unknown}', await call(`${a}/nodes/${enc('class:does.not.Exist')}`, { token }));
    const files = await call(`${a}/files?page=0&size=5`, { token });
    report('GET /files', files);
    report('GET /files?language=Nope', await call(`${a}/files?language=Nope`, { token }));
    report('GET /files?size=500', await call(`${a}/files?size=500`, { token }));
    const f = files.json?.items?.[0];
    if (f) report('GET /files/{id}', await call(`${a}/files/${enc(f.id)}`, { token }));
    const c = byType('CLASS');
    if (c) report('GET /classes/{id}', await call(`${a}/classes/${enc(c.id)}`, { token }));
    const i = byType('INTERFACE');
    if (i) report('GET /classes/{interface id}', await call(`${a}/classes/${enc(i.id)}`, { token }));
    report('DELETE /analyses/{random}', await call('/analyses/00000000-0000-0000-0000-000000000000', { token, method: 'DELETE' }));
  }

  if (env.ARCHYRA_ADMIN_EMAIL && env.ARCHYRA_ADMIN_PASSWORD) {
    const admin = await login(env.ARCHYRA_ADMIN_EMAIL, env.ARCHYRA_ADMIN_PASSWORD, 'admin');
    report('GET /users/me (admin)', await call('/users/me', { token: admin }));
    report('GET /admin/stats', await call('/admin/stats', { token: admin }));
    const users = await call('/admin/users?page=0&size=50', { token: admin });
    report('GET /admin/users', users);
    const u = users.json?.items?.[0];
    if (u) report('GET /admin/users/{id}', await call(`/admin/users/${u.id}`, { token: admin }));
    report('GET /admin/users/{random}', await call('/admin/users/00000000-0000-0000-0000-000000000000', { token: admin }));
  } else {
    console.log('\nARCHYRA_ADMIN_* not set: admin endpoints not probed.');
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : 'probe failed');
  process.exit(1);
});
