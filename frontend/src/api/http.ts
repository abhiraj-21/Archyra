import { hasCode } from './errors';
import { refreshSession } from './auth';
import { expireSession } from './session';
import { readResponse, send, type RequestOptions } from './transport';

/**
 * The one way the app calls the API. On `401 AUTHENTICATION_REQUIRED` it runs the shared single-flight refresh and
 * retries the original request exactly once. If the refresh fails the session ends (the session provider clears
 * the cache and sends the user to /login). Auth routes pass `withToken: false` and are never refreshed.
 */
export async function http<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options);
  if (response.status !== 401 || options.withToken === false) return readResponse<T>(response);

  const error = await readResponse<T>(response).catch((e: unknown) => e);
  if (!hasCode(error, 'AUTHENTICATION_REQUIRED')) throw error;

  const refreshed = await refreshSession();
  if (!refreshed) {
    expireSession();
    throw error;
  }
  const retry = await send(path, options);
  if (retry.status === 401) {
    // A fresh token was refused too: do not loop.
    const retryError = await readResponse<T>(retry).catch((e: unknown) => e);
    expireSession();
    throw retryError;
  }
  return readResponse<T>(retry);
}
