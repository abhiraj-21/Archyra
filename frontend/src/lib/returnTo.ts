/**
 * Where to go after signing in. Only same-origin app paths are accepted, so `?next=` can never become an open
 * redirect (`//evil.example`, `https://…`, `/\evil`).
 */
export const DEFAULT_AFTER_LOGIN = '/dashboard';

export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return DEFAULT_AFTER_LOGIN;
  if ([...next].some((c) => c.charCodeAt(0) < 32)) return DEFAULT_AFTER_LOGIN;
  const path = next.split('?')[0] ?? '';
  if (['/login', '/register', '/auth/callback'].includes(path)) return DEFAULT_AFTER_LOGIN;
  return next;
}

// The OAuth round trip is a full-page navigation, so the destination survives in session storage. It holds an
// app path only, never a token.
const KEY = 'archyra.returnTo';

export function stashReturnTo(next: string): void {
  try {
    // eslint-disable-next-line no-restricted-globals
    sessionStorage.setItem(KEY, safeNext(next));
  } catch {
    // Storage blocked: the callback falls back to the dashboard.
  }
}

export function takeReturnTo(): string {
  try {
    // eslint-disable-next-line no-restricted-globals
    const value = sessionStorage.getItem(KEY);
    // eslint-disable-next-line no-restricted-globals
    sessionStorage.removeItem(KEY);
    return safeNext(value);
  } catch {
    return DEFAULT_AFTER_LOGIN;
  }
}
