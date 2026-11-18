import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { abortAuthRequests, resetAuthStateForTests, refreshSession } from './auth';
import { http } from './http';
import { clearAccessToken, getAccessToken, onSessionExpired, setAccessToken } from './session';

// Inline test inputs only: a scripted fetch that answers by path.
type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;
const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });

let calls: { url: string; init: RequestInit }[] = [];
function stubFetch(handler: Handler) {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return handler(url, init);
  }));
}
const count = (path: string) => calls.filter((c) => c.url.startsWith(`/api/v1${path}`)).length;
const header = (init: RequestInit, name: string) => (init.headers as Record<string, string>)[name];

beforeEach(() => {
  resetAuthStateForTests();
  clearAccessToken();
  onSessionExpired(null);
});
afterEach(() => vi.unstubAllGlobals());

describe('http 401 handling', () => {
  it('refreshes once for concurrent 401s and retries each request once', async () => {
    setAccessToken('old');
    let refreshes = 0;
    stubFetch(async (url, init) => {
      if (url.startsWith('/api/v1/auth/csrf')) return json(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-1' });
      if (url.startsWith('/api/v1/auth/refresh')) {
        refreshes += 1;
        expect(header(init, 'X-XSRF-TOKEN')).toBe('csrf-1');
        await new Promise((r) => setTimeout(r, 5));
        return json(200, { accessToken: 'new', tokenType: 'Bearer', expiresIn: 900 });
      }
      return header(init, 'Authorization') === 'Bearer new'
        ? json(200, { ok: url })
        : json(401, { code: 'AUTHENTICATION_REQUIRED', message: 'x' });
    });

    const results = await Promise.all([http('/usage'), http('/users/me'), http('/analyses')]);
    expect(results).toHaveLength(3);
    expect(refreshes).toBe(1);
    expect(count('/usage')).toBe(2);
    expect(count('/users/me')).toBe(2);
    expect(getAccessToken()).toBe('new');
  });

  it('expires the session and does not loop when the refresh fails', async () => {
    setAccessToken('old');
    const expired = vi.fn();
    onSessionExpired(expired);
    stubFetch((url) => {
      if (url.startsWith('/api/v1/auth/csrf')) return json(200, { headerName: 'X-XSRF-TOKEN', token: 't' });
      if (url.startsWith('/api/v1/auth/refresh')) return json(401, { code: 'REFRESH_TOKEN_INVALID', message: 'x' });
      return json(401, { code: 'AUTHENTICATION_REQUIRED', message: 'x' });
    });

    await expect(http('/usage')).rejects.toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' });
    expect(count('/usage')).toBe(1);
    expect(count('/auth/refresh')).toBe(1);
    expect(expired).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBeNull();
  });

  it('retries the original request only once even if the new token is refused', async () => {
    setAccessToken('old');
    const expired = vi.fn();
    onSessionExpired(expired);
    stubFetch((url) => {
      if (url.startsWith('/api/v1/auth/csrf')) return json(200, { headerName: 'X-XSRF-TOKEN', token: 't' });
      if (url.startsWith('/api/v1/auth/refresh')) return json(200, { accessToken: 'new', tokenType: 'Bearer', expiresIn: 900 });
      return json(401, { code: 'AUTHENTICATION_REQUIRED', message: 'x' });
    });
    await expect(http('/usage')).rejects.toMatchObject({ code: 'AUTHENTICATION_REQUIRED' });
    expect(count('/usage')).toBe(2);
    expect(count('/auth/refresh')).toBe(1);
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it('never refreshes for auth routes', async () => {
    stubFetch(() => json(401, { code: 'INVALID_CREDENTIALS', message: 'x' }));
    await expect(http('/auth/login', { method: 'POST', body: {}, withToken: false })).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
    expect(count('/auth/refresh')).toBe(0);
  });

  it('does not refresh on a 401 with another code', async () => {
    setAccessToken('t');
    stubFetch(() => json(401, { code: 'SOMETHING_ELSE', message: 'x' }));
    await expect(http('/usage')).rejects.toMatchObject({ code: 'SOMETHING_ELSE' });
    expect(count('/auth/refresh')).toBe(0);
  });
});

describe('refreshSession', () => {
  it('never retries a refresh that got 401', async () => {
    stubFetch((url) =>
      url.startsWith('/api/v1/auth/csrf')
        ? json(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-1' })
        : json(401, { code: 'REFRESH_TOKEN_INVALID', message: 'x' }),
    );
    await expect(refreshSession()).resolves.toBe(false);
    expect(count('/auth/refresh')).toBe(1);
    expect(count('/auth/csrf')).toBe(1);
  });

  it('abortAuthRequests cancels a hung refresh for real and lets the next one start fresh', async () => {
    let refreshCalls = 0;
    stubFetch((url, init) => {
      if (url.startsWith('/api/v1/auth/csrf')) return json(200, { headerName: 'X-XSRF-TOKEN', token: 't' });
      refreshCalls += 1;
      if (refreshCalls > 1) return json(200, { accessToken: 'second', tokenType: 'Bearer', expiresIn: 900 });
      return new Promise<Response>((_, reject) => {
        init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    });
    const hung = refreshSession();
    const hungResult = hung.then(() => 'resolved', () => 'rejected');
    await new Promise((r) => setTimeout(r, 5));
    abortAuthRequests();
    await expect(hungResult).resolves.toBe('rejected');
    await expect(refreshSession()).resolves.toBe(true);
    expect(refreshCalls).toBe(2);
    expect(getAccessToken()).toBe('second');
  });

  it('refetches the CSRF token once on CSRF_TOKEN_INVALID', async () => {
    let csrfCalls = 0;
    stubFetch((url, init) => {
      if (url.startsWith('/api/v1/auth/csrf')) {
        csrfCalls += 1;
        return json(200, { headerName: 'X-XSRF-TOKEN', token: `csrf-${csrfCalls}` });
      }
      return header(init, 'X-XSRF-TOKEN') === 'csrf-2'
        ? json(200, { accessToken: 'fresh', tokenType: 'Bearer', expiresIn: 900 })
        : json(403, { code: 'CSRF_TOKEN_INVALID', message: 'x' });
    });
    await expect(refreshSession()).resolves.toBe(true);
    expect(csrfCalls).toBe(2);
    expect(getAccessToken()).toBe('fresh');
  });

  it('rejects with NETWORK_ERROR instead of signing out when the server is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }));
    await expect(refreshSession()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });

  it('normalises Retry-After and non-JSON bodies', async () => {
    setAccessToken('t');
    stubFetch(() => json(429, { code: 'RATE_LIMITED', message: 'x' }, { 'Retry-After': '42' }));
    await expect(http('/analyses', { method: 'POST', body: {} })).rejects.toMatchObject({ code: 'RATE_LIMITED', retryAfter: 42 });
    stubFetch(() => new Response('<html>bad gateway</html>', { status: 502 }));
    await expect(http('/usage')).rejects.toMatchObject({ status: 502, code: 'HTTP_502' });
  });
});
