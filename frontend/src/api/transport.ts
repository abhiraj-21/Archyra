import { ApiError, errorFromResponse, networkError, timeoutError } from './errors';
import { getAccessToken } from './session';

export const API_BASE = '/api/v1';
const DEFAULT_TIMEOUT_MS = 20_000;

export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  query?: Record<string, QueryValue>;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** Send the bearer token (default true). Auth routes send none. */
  withToken?: boolean;
  timeoutMs?: number;
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const qs = params.toString();
  return `${API_BASE}${path}${qs ? `?${qs}` : ''}`;
}

/**
 * One HTTP exchange, relative URL, cookies included. Resolves with the Response for any HTTP status; rejects only
 * with an ApiError for a network failure or timeout, or with the caller's own AbortError when it cancels.
 */
export async function send(path: string, options: RequestOptions = {}): Promise<Response> {
  const { method = 'GET', body, query, headers = {}, signal, withToken = true, timeoutMs = DEFAULT_TIMEOUT_MS } = options;

  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const onCallerAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) controller.abort();
    else signal.addEventListener('abort', onCallerAbort, { once: true });
  }

  const finalHeaders: Record<string, string> = { Accept: 'application/json', ...headers };
  if (body !== undefined) finalHeaders['Content-Type'] = 'application/json';
  const token = withToken ? getAccessToken() : null;
  if (token) finalHeaders.Authorization = `Bearer ${token}`;

  try {
    return await fetch(buildUrl(path, query), {
      method,
      headers: finalHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'include',
      signal: controller.signal,
    });
  } catch (error) {
    if (timedOut) throw timeoutError();
    if (signal?.aborted) throw error; // the caller cancelled (TanStack Query): let it see its own abort
    throw networkError();
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onCallerAbort);
  }
}

/** Reads a successful response body, or throws the normalised ApiError. */
export async function readResponse<T>(response: Response): Promise<T> {
  if (!response.ok) throw await errorFromResponse(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(response.status, 'UNEXPECTED_RESPONSE', 'The server sent an answer that could not be read.');
  }
}
