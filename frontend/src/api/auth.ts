import { ApiError, hasCode, isApiError } from './errors';
import { readResponse, send } from './transport';
import { clearAccessToken, setAccessToken } from './session';
import type { AuthResponse, CsrfResponse } from './types';

/**
 * Session core: CSRF, refresh and logout. The refresh token is an HttpOnly, SameSite=Strict cookie scoped to
 * /api/v1/auth that this code never sees. Refresh and logout authenticate by that cookie and are CSRF-protected:
 * GET /auth/csrf sets the readable XSRF-TOKEN cookie and returns the same token, which goes back as X-XSRF-TOKEN.
 */

let csrf: CsrfResponse | null = null;
let csrfInflight: Promise<CsrfResponse> | null = null;

async function fetchCsrf(): Promise<CsrfResponse> {
  if (!csrfInflight) {
    csrfInflight = send('/auth/csrf', { withToken: false })
      .then((res) => readResponse<CsrfResponse>(res))
      .then((value) => {
        csrf = value;
        return value;
      })
      .finally(() => {
        csrfInflight = null;
      });
  }
  return csrfInflight;
}

export async function ensureCsrf(): Promise<CsrfResponse> {
  return csrf ?? fetchCsrf();
}

/** POST to a cookie-authenticated auth route with the CSRF header; on CSRF_TOKEN_INVALID, refetch the token and retry once. */
async function postWithCsrf<T>(path: string): Promise<T> {
  let token = await ensureCsrf();
  let response = await send(path, { method: 'POST', withToken: false, headers: { [token.headerName]: token.token } });
  if (response.status === 403) {
    const error = await readResponse<T>(response).catch((e: unknown) => e);
    if (!hasCode(error, 'CSRF_TOKEN_INVALID')) throw error;
    token = await fetchCsrf();
    response = await send(path, { method: 'POST', withToken: false, headers: { [token.headerName]: token.token } });
  }
  return readResponse<T>(response);
}

let refreshInflight: Promise<boolean> | null = null;

/**
 * Single-flight refresh: every caller that needs a new access token while one refresh is running shares it. Two
 * parallel refreshes would present the same rotating cookie twice, which the backend treats as token theft and
 * answers by revoking the whole login family.
 *
 * Resolves true with a new token stored, false when there is no valid session (401/403). Rejects with an ApiError
 * only for a network failure or timeout, so a flaky connection never signs the user out.
 */
export function refreshSession(): Promise<boolean> {
  if (!refreshInflight) {
    refreshInflight = (async () => {
      try {
        const body = await postWithCsrf<AuthResponse>('/auth/refresh');
        setAccessToken(body.accessToken);
        return true;
      } catch (error) {
        if (isApiError(error) && (error.status === 401 || error.status === 403)) {
          clearAccessToken();
          return false;
        }
        throw error;
      }
    })().finally(() => {
      refreshInflight = null;
    });
  }
  return refreshInflight;
}

/** Ends the login family on the server. The local session is cleared whatever the server says. */
export async function logoutSession(): Promise<void> {
  try {
    await postWithCsrf<void>('/auth/logout');
  } catch (error) {
    if (!(error instanceof ApiError)) throw error;
  } finally {
    clearAccessToken();
  }
}

/** Test seam: forget the cached CSRF token. */
export function resetAuthStateForTests(): void {
  csrf = null;
  csrfInflight = null;
  refreshInflight = null;
}
